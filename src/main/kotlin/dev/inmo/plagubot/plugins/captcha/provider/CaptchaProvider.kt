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
import dev.inmo.tgbotapi.extensions.api.send.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitMessageDataCallbackQuery
import dev.inmo.tgbotapi.extensions.utils.asSlotMachineReelImage
import dev.inmo.tgbotapi.extensions.utils.calculateSlotMachineResult
import dev.inmo.tgbotapi.extensions.utils.formatting.*
import dev.inmo.tgbotapi.extensions.utils.shortcuts.executeUnsafe
import dev.inmo.tgbotapi.extensions.utils.types.buttons.*
import dev.inmo.tgbotapi.libraries.cache.admins.AdminsCacheAPI
import dev.inmo.tgbotapi.requests.DeleteMessage
import dev.inmo.tgbotapi.types.*
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.chat.ChatPermissions
import dev.inmo.tgbotapi.types.chat.*
import dev.inmo.tgbotapi.types.chat.User
import dev.inmo.tgbotapi.types.dice.SlotMachineDiceAnimationType
import dev.inmo.tgbotapi.types.message.abstracts.Message
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.flow.*
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
        leftRestrictionsPermissions: ChatPermissions,
        adminsApi: AdminsCacheAPI?,
        kickOnUnsuccess: Boolean
    )
}

private const val cancelData = "cancel"

private fun EntitiesBuilder.mention(user: User, defaultName: String = "User"): EntitiesBuilder {
    return mention(
        listOfNotNull(
            user.lastName.takeIf { it.isNotBlank() }, user.firstName.takeIf { it.isNotBlank() }
        ).takeIf {
            it.isNotEmpty()
        } ?.joinToString(" ") ?: defaultName,
        user
    )
}

private suspend fun BehaviourContext.sendAdminCanceledMessage(
    chat: Chat,
    captchaSolver: User,
    admin: User
) {
    safelyWithoutExceptions {
        sendTextMessage(
            chat,
            buildEntities {
                mention(admin, "Admin")
                regular(" cancelled captcha for ")
                mention(captchaSolver)
            }
        )
    }
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
                    mention(user)
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
        leftRestrictionsPermissions: ChatPermissions,
        adminsApi: AdminsCacheAPI?,
        kickOnUnsuccess: Boolean
    ) {
        val userBanDateTime = eventDateTime + checkTimeSpan
        val authorized = Channel<User>(newUsers.size)
        val messagesToDelete = Channel<Message>(Channel.UNLIMITED)
        val subContexts = newUsers.map { user ->
            createSubContextAndDoWithUpdatesFilter (stopOnCompletion = false) {
                val sentMessage = sendTextMessage(
                    chat,
                    buildEntities {
                        mention(user)
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
                        val userClicked = waitMessageDataCallbackQuery().filter { it.user.id == user.id && it.message.messageId == sentDice.messageId }.first()

                        when {
                            userClicked.data == leftToClick.first() -> {
                                clicked[3 - leftToClick.size] = leftToClick.removeAt(0)
                                if (clicked.contains(null)) {
                                    safelyWithoutExceptions { answerCallbackQuery(userClicked, "Ok, next one") }
                                    editMessageReplyMarkup(sentDice, slotMachineReplyMarkup(clicked[0], clicked[1], clicked[2]))
                                } else {
                                    safelyWithoutExceptions { answerCallbackQuery(userClicked, "Thank you and welcome", showAlert = true) }
                                    safelyWithoutExceptions { deleteMessage(sentMessage) }
                                    safelyWithoutExceptions { deleteMessage(sentDice) }
                                }
                            }
                            else -> safelyWithoutExceptions { answerCallbackQuery(userClicked, "Nope") }
                        }
                    }
                    authorized.send(user)
                    safelyWithoutExceptions { restrictChatMember(chat, user, permissions = leftRestrictionsPermissions) }
                    stop()
                }

                this to user
            }
        }

        delay((userBanDateTime - eventDateTime).millisecondsLong)

        authorized.close()
        val authorizedUsers = authorized.toList()

        subContexts.forEach { (context, user) ->
            if (user !in authorizedUsers) {
                context.stop()
                if (kickOnUnsuccess) {
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
    val buttonText: String = "Press me\uD83D\uDE0A"
) : CaptchaProvider() {
    @Transient
    private val checkTimeSpan = checkTimeSeconds.seconds

    override suspend fun BehaviourContext.doAction(
        eventDateTime: DateTime,
        chat: GroupChat,
        newUsers: List<User>,
        leftRestrictionsPermissions: ChatPermissions,
        adminsApi: AdminsCacheAPI?,
        kickOnUnsuccess: Boolean
    ) {
        val userBanDateTime = eventDateTime + checkTimeSpan
        newUsers.map { user ->
            launchSafelyWithoutExceptions {
                createSubContext(this).doInContext(stopOnCompletion = false) {
                    val callbackData = uuid4().toString()
                    val sentMessage = sendTextMessage(
                        chat,
                        buildEntities {
                            mention(user)
                            regular(", $captchaText")
                        },
                        replyMarkup = inlineKeyboard {
                            row {
                                dataButton(buttonText, callbackData)
                            }
                            if (adminsApi != null) {
                                row {
                                    dataButton("Cancel (Admins only)", cancelData)
                                }
                            }
                        }
                    )

                    suspend fun removeRedundantMessages() {
                        safelyWithoutExceptions {
                            deleteMessage(sentMessage)
                        }
                    }

                    val job = launchSafely {
                        waitMessageDataCallbackQuery().filter { query ->
                            val baseCheck = query.message.messageId == sentMessage.messageId
                            val userAnswered = query.user.id == user.id && query.data == callbackData
                            val adminCanceled = (query.data == cancelData && (adminsApi ?.isAdmin(sentMessage.chat.id, query.user.id)) == true)
                            if (baseCheck && adminCanceled) {
                                sendAdminCanceledMessage(
                                    sentMessage.chat,
                                    user,
                                    query.user
                                )
                            }
                            baseCheck && (adminCanceled || userAnswered)
                        }.first()

                        removeRedundantMessages()
                        safelyWithoutExceptions { restrictChatMember(chat, user, permissions = leftRestrictionsPermissions) }
                        stop()
                    }

                    delay((userBanDateTime - eventDateTime).millisecondsLong)

                    if (job.isActive) {
                        job.cancel()
                        if (kickOnUnsuccess) {
                            banUser(chat, user, leftRestrictionsPermissions)
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
    val attempts: Int = 3
) : CaptchaProvider() {
    @Transient
    private val checkTimeSpan = checkTimeSeconds.seconds

    override suspend fun BehaviourContext.doAction(
        eventDateTime: DateTime,
        chat: GroupChat,
        newUsers: List<User>,
        leftRestrictionsPermissions: ChatPermissions,
        adminsApi: AdminsCacheAPI?,
        kickOnUnsuccess: Boolean
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
                            mention(user)
                            regular(", $captchaText ")
                            bold(callbackData.second)
                        },
                        replyMarkup = inlineKeyboard {
                            answers.map {
                                CallbackDataInlineKeyboardButton(it.toString(), it.toString())
                            }.chunked(3).forEach(::add)
                            if (adminsApi != null) {
                                row {
                                    dataButton("Cancel (Admins only)", cancelData)
                                }
                            }
                        }
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
                                when {
                                    it -> safelyWithoutExceptions { restrictChatMember(chat, user, permissions = leftRestrictionsPermissions) }
                                    kickOnUnsuccess -> banUser(chat, user, leftRestrictionsPermissions)
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
                    waitMessageDataCallbackQuery().takeWhile { leftAttempts > 0 }.filter { query ->
                        val baseCheck = query.message.messageId == sentMessage.messageId
                        val dataCorrect = (query.user.id == user.id && query.data == correctAnswer)
                        val adminCanceled = (query.data == cancelData && (adminsApi ?.isAdmin(sentMessage.chat.id, query.user.id)) == true)
                        baseCheck && if (dataCorrect || adminCanceled) {
                            banJob.cancel()
                            if (adminCanceled) {
                                sendAdminCanceledMessage(
                                    sentMessage.chat,
                                    user,
                                    query.user
                                )
                            }
                            true
                        } else {
                            leftAttempts--
                            if (leftAttempts > 0) {
                                answerCallbackQuery(query, leftRetriesText + leftAttempts)
                            }
                            false
                        }
                    }.firstOrNull()

                    callback(leftAttempts > 0)
                }
            }
        }.joinAll()
    }
}

