package dev.inmo.plagubot.plugins.captcha

import dev.inmo.micro_utils.coroutines.*
import dev.inmo.micro_utils.repos.create
import dev.inmo.plagubot.Plugin
import dev.inmo.plagubot.plugins.captcha.db.CaptchaChatsSettingsRepo
import dev.inmo.plagubot.plugins.captcha.settings.ChatSettings
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.answers.answerCallbackQuery
import dev.inmo.tgbotapi.extensions.api.chat.members.*
import dev.inmo.tgbotapi.extensions.api.deleteMessage
import dev.inmo.tgbotapi.extensions.api.edit.ReplyMarkup.editMessageReplyMarkup
import dev.inmo.tgbotapi.extensions.api.send.media.reply
import dev.inmo.tgbotapi.extensions.api.send.sendDice
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitBaseInlineQuery
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitDataCallbackQuery
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.updateshandlers.FlowsUpdatesFilter
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onNewChatMembers
import dev.inmo.tgbotapi.extensions.utils.*
import dev.inmo.tgbotapi.extensions.utils.formatting.buildEntities
import dev.inmo.tgbotapi.extensions.utils.formatting.regular
import dev.inmo.tgbotapi.extensions.utils.shortcuts.executeUnsafe
import dev.inmo.tgbotapi.libraries.cache.admins.AdminsCacheAPI
import dev.inmo.tgbotapi.libraries.cache.admins.adminsPlugin
import dev.inmo.tgbotapi.requests.DeleteMessage
import dev.inmo.tgbotapi.types.BotCommand
import dev.inmo.tgbotapi.types.MessageEntity.textsources.mention
import dev.inmo.tgbotapi.types.User
import dev.inmo.tgbotapi.types.chat.ChatPermissions
import dev.inmo.tgbotapi.types.chat.LeftRestrictionsChatPermissions
import dev.inmo.tgbotapi.types.chat.abstracts.Chat
import dev.inmo.tgbotapi.types.chat.abstracts.PublicChat
import dev.inmo.tgbotapi.types.dice.SlotMachineDiceAnimationType
import dev.inmo.tgbotapi.types.message.abstracts.*
import dev.inmo.tgbotapi.types.message.content.abstracts.MessageContent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database

private const val enableAutoDeleteCommands = "captcha_auto_delete_commands_on"
private const val disableAutoDeleteCommands = "captcha_auto_delete_commands_off"

private suspend fun AdminsCacheAPI.verifyMessageFromAdmin(message: CommonMessage<*>) = when (message) {
    is CommonGroupContentMessage<*> -> getChatAdmins(message.chat.id) ?.any { it.user.id == message.user.id } == true
    is AnonymousGroupContentMessage<*> -> true
    else -> false
}

@Serializable
class CaptchaBotPlugin : Plugin {
    override suspend fun getCommands(): List<BotCommand> = listOf(
        BotCommand(
            enableAutoDeleteCommands,
            "Enable auto removing of commands addressed to captcha plugin"
        ),
        BotCommand(
            disableAutoDeleteCommands,
            "Disable auto removing of commands addressed to captcha plugin"
        )
    )

    override suspend fun BehaviourContext.invoke(
        database: Database,
        params: Map<String, Any>
    ) {
        val repo = CaptchaChatsSettingsRepo(database)
        val adminsAPI = params.adminsPlugin ?.adminsAPI(database)
        suspend fun Chat.settings() = repo.getById(id) ?: repo.create(ChatSettings(id)).first()

        onNewChatMembers(
            additionalFilter = {
                it.chat.asPublicChat() != null
            },
            includeFilterByChatInBehaviourSubContext = false
        ) {
            safelyWithoutExceptions { deleteMessage(it) }
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
            val settings = it.chat.settings() ?: return@onNewChatMembers
            val userBanDateTime = eventDateTime + settings.checkTimeSpan
            val authorized = Channel<User>(newUsers.size)
            val messagesToDelete = Channel<Message>(Channel.UNLIMITED)
            val subContexts = newUsers.map {
                doInSubContext(stopOnCompletion = false) {
                    val sentMessage = sendTextMessage(
                        chat,
                        buildEntities {
                            +it.mention(it.firstName)
                            regular(", ${settings.captchaText}")
                        }
                    ).also { messagesToDelete.send(it) }
                    val sentDice = sendDice(
                        sentMessage.chat,
                        SlotMachineDiceAnimationType,
                        replyToMessageId = sentMessage.messageId,
                        replyMarkup = slotMachineReplyMarkup()
                    ).also { messagesToDelete.send(it) }
                    val reels = sentDice.content.dice.calculateSlotMachineResult()!!
                    val leftToClick = mutableListOf(
                        reels.left.asSlotMachineReelImage.text,
                        reels.center.asSlotMachineReelImage.text,
                        reels.right.asSlotMachineReelImage.text
                    )

                    launch {
                        val clicked = arrayOf<String?>(null, null, null)
                        while (leftToClick.isNotEmpty()) {
                            val userClicked = waitDataCallbackQuery { if (user.id == it.id) this else null }.first()
                            if (userClicked.data == leftToClick.first()) {
                                clicked[3 - leftToClick.size] = leftToClick.removeAt(0)
                                if (clicked.contains(null)) {
                                    safelyWithoutExceptions { answerCallbackQuery(userClicked, "Ok, next one") }
                                    editMessageReplyMarkup(sentDice, slotMachineReplyMarkup(clicked[0], clicked[1], clicked[2]))
                                } else {
                                    safelyWithoutExceptions { answerCallbackQuery(userClicked, "Thank you and welcome", showAlert = true) }
                                    safelyWithoutExceptions { deleteMessage(sentMessage) }
                                    safelyWithoutExceptions { deleteMessage(sentDice) }
                                }
                            } else {
                                safelyWithoutExceptions { answerCallbackQuery(userClicked, "Nope") }
                            }
                        }
                        authorized.send(it)
                        safelyWithoutExceptions { restrictChatMember(chat, it, permissions = LeftRestrictionsChatPermissions) }
                        stop()
                    }

                    this to it
                }
            }

            delay((userBanDateTime - eventDateTime).millisecondsLong)

            authorized.close()
            val authorizedUsers = authorized.toList()

            subContexts.forEach { (context, user) ->
                if (user !in authorizedUsers) {
                    context.stop()
                    safelyWithoutExceptions { kickChatMember(chat, user) }
                }
            }
            messagesToDelete.close()
            for (message in messagesToDelete) {
                executeUnsafe(DeleteMessage(message.chat.id, message.messageId), retries = 0)
            }
        }

        if (adminsAPI != null) {
            suspend fun <T : MessageContent> CommonMessage<T>.doAfterVerification(block: suspend () -> Unit) {
                val chat = chat

                if (chat is PublicChat) {
                    val verified = adminsAPI.verifyMessageFromAdmin(this)
                    if (verified) {
                        block()
                    }
                }
            }
            onCommand(
                enableAutoDeleteCommands,
                requireOnlyCommandInMessage = false
            ) { message ->
                message.doAfterVerification {
                    val settings = message.chat.settings()

                    repo.update(
                        message.chat.id,
                        settings.copy(autoRemoveCommands = true)
                    )

                    deleteMessage(message)
                }
            }
            onCommand(
                disableAutoDeleteCommands,
                requireOnlyCommandInMessage = false
            ) { message ->
                message.doAfterVerification {
                    val settings = message.chat.settings()

                    repo.update(
                        message.chat.id,
                        settings.copy(autoRemoveCommands = false)
                    )
                }
            }
        }
    }
}
