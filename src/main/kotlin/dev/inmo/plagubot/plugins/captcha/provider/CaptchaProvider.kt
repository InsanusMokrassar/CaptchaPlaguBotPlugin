package dev.inmo.plagubot.plugins.captcha.provider

import com.benasher44.uuid.uuid4
import com.soywiz.klock.DateTime
import com.soywiz.klock.seconds
import dev.inmo.micro_utils.coroutines.safelyWithoutExceptions
import dev.inmo.plagubot.plugins.captcha.slotMachineReplyMarkup
import dev.inmo.tgbotapi.extensions.api.answers.answerCallbackQuery
import dev.inmo.tgbotapi.extensions.api.chat.members.kickChatMember
import dev.inmo.tgbotapi.extensions.api.chat.members.restrictChatMember
import dev.inmo.tgbotapi.extensions.api.deleteMessage
import dev.inmo.tgbotapi.extensions.api.edit.ReplyMarkup.editMessageReplyMarkup
import dev.inmo.tgbotapi.extensions.api.send.sendDice
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitDataCallbackQuery
import dev.inmo.tgbotapi.extensions.utils.asSlotMachineReelImage
import dev.inmo.tgbotapi.extensions.utils.calculateSlotMachineResult
import dev.inmo.tgbotapi.extensions.utils.formatting.buildEntities
import dev.inmo.tgbotapi.extensions.utils.formatting.regular
import dev.inmo.tgbotapi.extensions.utils.shortcuts.executeUnsafe
import dev.inmo.tgbotapi.extensions.utils.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.requests.DeleteMessage
import dev.inmo.tgbotapi.types.MessageEntity.textsources.mention
import dev.inmo.tgbotapi.types.Seconds
import dev.inmo.tgbotapi.types.User
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.chat.LeftRestrictionsChatPermissions
import dev.inmo.tgbotapi.types.chat.abstracts.GroupChat
import dev.inmo.tgbotapi.types.dice.SlotMachineDiceAnimationType
import dev.inmo.tgbotapi.types.message.abstracts.Message
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
sealed class CaptchaProvider {
    abstract suspend fun BehaviourContext.doAction(
        eventDateTime: DateTime,
        chat: GroupChat,
        newUsers: List<User>
    )
}

@Serializable
data class SlotMachineCaptchaProvider(
    val checkTimeSeconds: Seconds = 300,
    val captchaText: String = "solve this captcha: "
) : CaptchaProvider() {
    @Transient
    private val checkTimeSpan = checkTimeSeconds.seconds

    override suspend fun BehaviourContext.doAction(
        eventDateTime: DateTime,
        chat: GroupChat,
        newUsers: List<User>
    ) {
        val userBanDateTime = eventDateTime + checkTimeSpan
        val authorized = Channel<User>(newUsers.size)
        val messagesToDelete = Channel<Message>(Channel.UNLIMITED)
        val subContexts = newUsers.map {
            doInSubContext(stopOnCompletion = false) {
                val sentMessage = sendTextMessage(
                    chat,
                    buildEntities {
                        +it.mention(it.firstName)
                        regular(", ${captchaText}")
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
}

@Serializable
data class SimpleCaptchaProvider(
    val checkTimeSeconds: Seconds = 60,
    val captchaText: String = "press this button to pass captcha:",
    val buttonText: String = "Press me\uD83D\uDE0A",
    val kick: Boolean = true
) : CaptchaProvider() {
    @Transient
    private val checkTimeSpan = checkTimeSeconds.seconds

    override suspend fun BehaviourContext.doAction(
        eventDateTime: DateTime,
        chat: GroupChat,
        newUsers: List<User>
    ) {
        val userBanDateTime = eventDateTime + checkTimeSpan
        newUsers.mapNotNull {
            safelyWithoutExceptions {
                launch {
                    doInSubContext(stopOnCompletion = false) {
                        val callbackData = uuid4().toString()
                        val sentMessage = sendTextMessage(
                            chat,
                            buildEntities {
                                +it.mention(it.firstName)
                                regular(", $captchaText")
                            },
                            replyMarkup = InlineKeyboardMarkup(
                                CallbackDataInlineKeyboardButton(buttonText, callbackData)
                            )
                        )

                        suspend fun removeRedundantMessages() {
                            safelyWithoutExceptions {
                                deleteMessage(sentMessage)
                            }
                        }

                        val job = parallel {
                            waitDataCallbackQuery {
                                if (it.id == user.id && this.data == callbackData) {
                                    this
                                } else {
                                    null
                                }
                            }.first()

                            removeRedundantMessages()
                            safelyWithoutExceptions { restrictChatMember(chat, it, permissions = LeftRestrictionsChatPermissions) }
                            stop()
                        }

                        delay((userBanDateTime - eventDateTime).millisecondsLong)

                        if (job.isActive) {
                            job.cancel()
                            if (kick) {
                                safelyWithoutExceptions { kickChatMember(chat, it) }
                            }
                        }
                        stop()
                    }
                }
            }
        }.joinAll()
    }
}

