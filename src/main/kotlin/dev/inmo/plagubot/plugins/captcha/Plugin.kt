package dev.inmo.plagubot.plugins.captcha

import dev.inmo.micro_utils.coroutines.safelyWithoutExceptions
import dev.inmo.micro_utils.repos.create
import dev.inmo.plagubot.Plugin
import dev.inmo.plagubot.plugins.captcha.db.CaptchaChatsSettingsRepo
import dev.inmo.plagubot.plugins.captcha.settings.ChatSettings
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.answers.answerCallbackQuery
import dev.inmo.tgbotapi.extensions.api.chat.members.*
import dev.inmo.tgbotapi.extensions.api.send.media.reply
import dev.inmo.tgbotapi.extensions.api.send.sendDice
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitBaseInlineQuery
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitDataCallbackQuery
import dev.inmo.tgbotapi.updateshandlers.FlowsUpdatesFilter
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onNewChatMembers
import dev.inmo.tgbotapi.extensions.utils.*
import dev.inmo.tgbotapi.extensions.utils.formatting.buildEntities
import dev.inmo.tgbotapi.extensions.utils.formatting.regular
import dev.inmo.tgbotapi.types.MessageEntity.textsources.mention
import dev.inmo.tgbotapi.types.User
import dev.inmo.tgbotapi.types.chat.ChatPermissions
import dev.inmo.tgbotapi.types.dice.SlotMachineDiceAnimationType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database

@Serializable
class CaptchaBotPlugin : Plugin {
    override suspend fun BehaviourContext.invoke(
        database: Database,
        params: Map<String, Any>
    ) {
        val repo = CaptchaChatsSettingsRepo(database)
        onNewChatMembers(
            additionalFilter = {
                it.chat.asPublicChat() != null
            }
        ) {
            val eventDateTime = it.date
            val chat = it.chat.requirePublicChat()
            val newUsers = it.chatEvent.members
            newUsers.forEach { user ->
                restrictChatMember(
                    chat,
                    user,
                    permissions = ChatPermissions()
                )
            }
            val settings = repo.getById(it.chat.id) ?: repo.create(ChatSettings(it.chat.id)).firstOrNull() ?: return@onNewChatMembers
            val userBanDateTime = eventDateTime + settings.checkTimeSpan
            val authorized = Channel<User>(newUsers.size)
            val subContexts = newUsers.map {
                doInSubContext {
                    val sentMessage = sendTextMessage(
                        chat,
                        buildEntities {
                            +it.mention(it.firstName)
                            regular(settings.captchaText)
                        }
                    )
                    val sentDice = sendDice(
                        sentMessage.chat,
                        SlotMachineDiceAnimationType,
                        replyToMessageId = sentMessage.messageId,
                        replyMarkup = slotMachineReplyMarkup()
                    )
                    val reels = sentDice.content.dice.calculateSlotMachineResult()!!
                    val leftToClick = mutableListOf(
                        reels.left.asSlotMachineReelImage.text,
                        reels.center.asSlotMachineReelImage.text,
                        reels.right.asSlotMachineReelImage.text
                    )

                    launch {
                        while (leftToClick.isNotEmpty()) {
                            val userClicked = waitDataCallbackQuery { if (user.id == it.id) this else null }.first()
                            answerCallbackQuery(userClicked, "⏳")
                            if (userClicked.data == leftToClick.first()) {
                                leftToClick.removeAt(0)
                            }
                        }
                        authorized.send(it)
                        safelyWithoutExceptions { restrictChatMember(chat, it, permissions = authorizedUserChatPermissions) }
                        stop()
                    }

                    this to it
                }
            }

            delay((userBanDateTime - eventDateTime).millisecondsLong)

            val authorizedUsers = authorized.toList()
            authorized.close()

            subContexts.forEach { (context, user) ->
                if (user !in authorizedUsers) {
                    context.stop()
                    safelyWithoutExceptions { kickChatMember(chat, user) }
                }
            }
        }
    }
}
