package dev.inmo.plagubot.plugins.captcha.provider

import com.benasher44.uuid.uuid4
import com.soywiz.klock.DateTime
import com.soywiz.klock.seconds
import dev.inmo.micro_utils.coroutines.*
import dev.inmo.plagubot.plugins.captcha.slotMachineReplyMarkup
import dev.inmo.tgbotapi.extensions.api.answers.answerCallbackQuery
import dev.inmo.tgbotapi.extensions.api.chat.members.*
import dev.inmo.tgbotapi.extensions.api.deleteMessage
import dev.inmo.tgbotapi.extensions.api.edit.reply_markup.editMessageReplyMarkup
import dev.inmo.tgbotapi.extensions.api.send.sendDice
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitDataCallbackQuery
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitMessageDataCallbackQuery
import dev.inmo.tgbotapi.extensions.utils.asSlotMachineReelImage
import dev.inmo.tgbotapi.extensions.utils.calculateSlotMachineResult
import dev.inmo.tgbotapi.extensions.utils.extensions.raw.message
import dev.inmo.tgbotapi.extensions.utils.formatting.*
import dev.inmo.tgbotapi.extensions.utils.shortcuts.executeUnsafe
import dev.inmo.tgbotapi.extensions.utils.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.requests.DeleteMessage
import dev.inmo.tgbotapi.types.*
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.chat.ChatPermissions
import dev.inmo.tgbotapi.types.chat.*
import dev.inmo.tgbotapi.types.chat.User
import dev.inmo.tgbotapi.types.dice.SlotMachineDiceAnimationType
import dev.inmo.tgbotapi.types.message.abstracts.Message
import dev.inmo.tgbotapi.types.message.textsources.mention
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.random.Random

@Serializable
sealed class CaptchaProvider {
    abstract suspend fun BehaviourContext.doAction(
        eventDateTime: DateTime,
        chat: GroupChat,
        newUsers: List<User>,
        leftRestrictionsPermissions: ChatPermissions
    )
}

private suspend fun BehaviourContext.banUser(
    chat: PublicChat,
    user: User,
    leftRestrictionsPermissions: ChatPermissions,
    onFailure: suspend BehaviourContext.(Throwable) -> Unit = {
        safelyWithResult {
            sendTextMessage(
                chat,
                buildEntities(" ") {
                    user.mention(
                        listOfNotNull(
                            user.lastName.takeIf { it.isNotBlank() }, user.firstName.takeIf { it.isNotBlank() }
                        ).takeIf {
                            it.isNotEmpty()
                        } ?.joinToString(" ") ?: "User"
                    )
                    +"failed captcha"
                }
            )
        }
    }
): Result<Boolean> = safelyWithResult {
    restrictChatMember(chat, user, permissions = leftRestrictionsPermissions)
    banChatMember(chat, user)
}.onFailure {
    onFailure(it)
}

@Serializable
data class SlotMachineCaptchaProvider(
    val checkTimeSeconds: Seconds = 300,
    val captchaText: String = "solve this captcha: ",
    val kick: Boolean = true
) : CaptchaProvider() {
    @Transient
    private val checkTimeSpan = checkTimeSeconds.seconds

    override suspend fun BehaviourContext.doAction(
        eventDateTime: DateTime,
        chat: GroupChat,
        newUsers: List<User>,
        leftRestrictionsPermissions: ChatPermissions
    ) {
        val userBanDateTime = eventDateTime + checkTimeSpan
        val authorized = Channel<User>(newUsers.size)
        val messagesToDelete = Channel<Message>(Channel.UNLIMITED)
        val subContexts = newUsers.map {
            createSubContextAndDoWithUpdatesFilter (stopOnCompletion = false) {
                val sentMessage = sendTextMessage(
                    chat,
                    buildEntities {
                        +it.mention(it.firstName)
                        regular(", $captchaText")
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
                        val userClicked = waitMessageDataCallbackQuery {
                            if (user.id == it.id && this.message.messageId == sentDice.messageId) this else null
                        }.first()
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
                    safelyWithoutExceptions { restrictChatMember(chat, it, permissions = leftRestrictionsPermissions) }
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
                if (kick) {
                    banUser(chat, user, leftRestrictionsPermissions)
                }
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
        newUsers: List<User>,
        leftRestrictionsPermissions: ChatPermissions
    ) {
        val userBanDateTime = eventDateTime + checkTimeSpan
        newUsers.map {
            launchSafelyWithoutExceptions {
                createSubContext(this).doInContext(stopOnCompletion = false) {
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

                    val job = launchSafely {
                        waitMessageDataCallbackQuery (
                            filter = { query ->
                                query.user.id == it.id && query.data == callbackData && query.message.messageId == sentMessage.messageId
                            }
                        ).first()

                        removeRedundantMessages()
                        safelyWithoutExceptions { restrictChatMember(chat, it, permissions = leftRestrictionsPermissions) }
                        stop()
                    }

                    delay((userBanDateTime - eventDateTime).millisecondsLong)

                    if (job.isActive) {
                        job.cancel()
                        if (kick) {
                            banUser(chat, it, leftRestrictionsPermissions)
                        }
                    }
                    stop()
                }
            }
        }.joinAll()
    }
}

private object ExpressionBuilder {
    sealed class ExpressionOperation {
        object PlusExpressionOperation : ExpressionOperation() {
            override fun asString(): String = "+"

            override fun Int.perform(other: Int): Int = plus(other)
        }
        object MinusExpressionOperation : ExpressionOperation() {
            override fun asString(): String = "-"

            override fun Int.perform(other: Int): Int = minus(other)
        }
        abstract fun asString(): String
        abstract fun Int.perform(other: Int): Int
    }
    private val experssions = listOf(ExpressionOperation.PlusExpressionOperation, ExpressionOperation.MinusExpressionOperation)

    private fun createNumber(max: Int) = Random.nextInt(max + 1)
    fun generateResult(max: Int, operationsNumber: Int = 1): Int {
        val operations = (0 until operationsNumber).map { experssions.random() }
        var current = createNumber(max)
        operations.forEach {
            val rightOne = createNumber(max)
            current = it.run { current.perform(rightOne) }
        }
        return current
    }
    fun createExpression(max: Int, operationsNumber: Int = 1): Pair<Int, String> {
        val operations = (0 until operationsNumber).map { experssions.random() }
        var current = createNumber(max)
        var numbersString = "$current"
        operations.forEach {
            val rightOne = createNumber(max)
            current = it.run { current.perform(rightOne) }
            numbersString += " ${it.asString()} $rightOne"
        }
        return current to numbersString
    }
}

@Serializable
data class ExpressionCaptchaProvider(
    val checkTimeSeconds: Seconds = 60,
    val captchaText: String = "Solve next captcha:",
    val leftRetriesText: String = "Nope, left retries: ",
    val maxPerNumber: Int = 10,
    val operations: Int = 2,
    val answers: Int = 6,
    val attempts: Int = 3,
    val kick: Boolean = true
) : CaptchaProvider() {
    @Transient
    private val checkTimeSpan = checkTimeSeconds.seconds

    override suspend fun BehaviourContext.doAction(
        eventDateTime: DateTime,
        chat: GroupChat,
        newUsers: List<User>,
        leftRestrictionsPermissions: ChatPermissions
    ) {
        val userBanDateTime = eventDateTime + checkTimeSpan
        newUsers.map { user ->
            launch {
                createSubContextAndDoWithUpdatesFilter {
                    val callbackData = ExpressionBuilder.createExpression(
                        maxPerNumber,
                        operations
                    )
                    val correctAnswer = callbackData.first.toString()
                    val answers = (0 until answers - 1).map {
                        ExpressionBuilder.generateResult(maxPerNumber, operations)
                    }.toMutableList().also { orderedAnswers ->
                        val correctAnswerPosition = Random.nextInt(orderedAnswers.size)
                        orderedAnswers.add(correctAnswerPosition, callbackData.first)
                    }.toList()
                    val sentMessage = sendTextMessage(
                        chat,
                        buildEntities {
                            +user.mention(user.firstName)
                            regular(", $captchaText ")
                            bold(callbackData.second)
                        },
                        replyMarkup = dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup(
                            answers.map {
                                CallbackDataInlineKeyboardButton(it.toString(), it.toString())
                            }.chunked(3)
                        )
                    )

                    suspend fun removeRedundantMessages() {
                        safelyWithoutExceptions {
                            deleteMessage(sentMessage)
                        }
                    }

                    var passed: Boolean? = null
                    val passedMutex = Mutex()
                    val callback: suspend (Boolean) -> Unit = {
                        passedMutex.withLock {
                            if (passed == null) {
                                removeRedundantMessages()
                                passed = it
                                if (it) {
                                    safelyWithoutExceptions { restrictChatMember(chat, user, permissions = leftRestrictionsPermissions) }
                                } else {
                                    if (kick) {
                                        banUser(chat, user, leftRestrictionsPermissions)
                                    }
                                }
                            }
                        }
                    }

                    val banJob = launch {
                        delay((userBanDateTime - eventDateTime).millisecondsLong)

                        if (passed == null) {
                            callback(false)
                            stop()
                        }
                    }

                    var leftAttempts = attempts
                    waitMessageDataCallbackQuery (
                        filter = { query ->
                            query.user.id == user.id && query.message.messageId == sentMessage.messageId
                        }
                    ) {
                        if (this.data != correctAnswer) {
                            leftAttempts--
                            if (leftAttempts < 1) {
                                this
                            } else {
                                answerCallbackQuery(this@waitMessageDataCallbackQuery, leftRetriesText + leftAttempts)
                                null
                            }
                        } else {
                            this
                        }
                    }.take(1)

                    banJob.cancel()

                    callback(leftAttempts > 0)
                }
            }
        }.joinAll()
    }
}

