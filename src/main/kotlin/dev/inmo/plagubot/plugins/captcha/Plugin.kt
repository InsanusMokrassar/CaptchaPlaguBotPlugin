package dev.inmo.plagubot.plugins.captcha

import dev.inmo.micro_utils.coroutines.*
import dev.inmo.micro_utils.repos.create
import dev.inmo.plagubot.Plugin
import dev.inmo.plagubot.plugins.captcha.db.CaptchaChatsSettingsRepo
import dev.inmo.plagubot.plugins.captcha.provider.*
import dev.inmo.plagubot.plugins.captcha.settings.ChatSettings
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.answers.answerCallbackQuery
import dev.inmo.tgbotapi.extensions.api.chat.members.*
import dev.inmo.tgbotapi.extensions.api.deleteMessage
import dev.inmo.tgbotapi.extensions.api.edit.ReplyMarkup.editMessageReplyMarkup
import dev.inmo.tgbotapi.extensions.api.send.*
import dev.inmo.tgbotapi.extensions.api.send.media.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitBaseInlineQuery
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitDataCallbackQuery
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.updateshandlers.FlowsUpdatesFilter
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onNewChatMembers
import dev.inmo.tgbotapi.extensions.utils.*
import dev.inmo.tgbotapi.extensions.utils.extensions.parseCommandsWithParams
import dev.inmo.tgbotapi.extensions.utils.formatting.buildEntities
import dev.inmo.tgbotapi.extensions.utils.formatting.regular
import dev.inmo.tgbotapi.extensions.utils.shortcuts.executeUnsafe
import dev.inmo.tgbotapi.libraries.cache.admins.*
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

private const val enableSlotMachineCaptcha = "captcha_use_slot_machine"
private const val enableSimpleCaptcha = "captcha_use_simple"
private const val enableExpressionCaptcha = "captcha_use_expression"

private val changeCaptchaMethodCommandRegex = Regex(
    "captcha_use_((slot_machine)|(simple)|(expression))"
)

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
        ),
        BotCommand(
            enableSlotMachineCaptcha,
            "Change captcha method to slot machine"
        ),
        BotCommand(
            enableSimpleCaptcha,
            "Change captcha method to simple button"
        ),
        BotCommand(
            enableExpressionCaptcha,
            "Change captcha method to expressions"
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
            val chat = it.chat.requireGroupChat()
            val newUsers = it.chatEvent.members
            newUsers.forEach { user ->
                restrictChatMember(
                    chat,
                    user,
                    permissions = ChatPermissions()
                )
            }
            val settings = it.chat.settings()
            settings.captchaProvider.apply { doAction(it.date, chat, newUsers) }
        }

        if (adminsAPI != null) {
            onCommand(changeCaptchaMethodCommandRegex) {
                if (adminsAPI.sentByAdmin(it) != true) {
                    return@onCommand
                }

                val settings = it.chat.settings()
                if (settings.autoRemoveCommands) {
                    safelyWithoutExceptions { deleteMessage(it) }
                }
                val commands = it.parseCommandsWithParams()
                val changeCommand = commands.keys.first {
                    println(it)
                    changeCaptchaMethodCommandRegex.matches(it)
                }
                println(changeCommand)
                val captcha = when {
                    changeCommand.startsWith(enableSimpleCaptcha) -> SimpleCaptchaProvider()
                    changeCommand.startsWith(enableExpressionCaptcha) -> ExpressionCaptchaProvider()
                    changeCommand.startsWith(enableSlotMachineCaptcha) -> SlotMachineCaptchaProvider()
                    else -> return@onCommand
                }
                val newSettings = settings.copy(captchaProvider = captcha)
                if (repo.contains(it.chat.id)) {
                    repo.update(it.chat.id, newSettings)
                } else {
                    repo.create(newSettings)
                }
                sendMessage(it.chat, "Settings updated").also { sent ->
                    delay(5000L)
                    deleteMessage(sent)
                }
            }

            onCommand(
                enableAutoDeleteCommands,
                requireOnlyCommandInMessage = false
            ) { message ->
                message.doAfterVerification(adminsAPI) {
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
                message.doAfterVerification(adminsAPI) {
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
