package dev.inmo.plagubot.plugins.captcha

import dev.inmo.micro_utils.coroutines.*
import dev.inmo.micro_utils.repos.create
import dev.inmo.plagubot.Plugin
import dev.inmo.plagubot.plugins.captcha.db.CaptchaChatsSettingsRepo
import dev.inmo.plagubot.plugins.captcha.provider.*
import dev.inmo.plagubot.plugins.captcha.settings.ChatSettings
import dev.inmo.tgbotapi.extensions.api.chat.get.getChat
import dev.inmo.tgbotapi.extensions.api.chat.members.*
import dev.inmo.tgbotapi.extensions.api.deleteMessage
import dev.inmo.tgbotapi.extensions.api.send.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.*
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onNewChatMembers
import dev.inmo.tgbotapi.extensions.utils.*
import dev.inmo.tgbotapi.extensions.utils.extensions.parseCommandsWithParams
import dev.inmo.tgbotapi.extensions.utils.extensions.sourceChat
import dev.inmo.tgbotapi.libraries.cache.admins.*
import dev.inmo.tgbotapi.types.BotCommand
import dev.inmo.tgbotapi.types.chat.RestrictionsChatPermissions
import dev.inmo.tgbotapi.types.chat.abstracts.extended.ExtendedGroupChat
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.Database
import org.koin.core.Koin
import org.koin.core.module.Module
import dev.inmo.tgbotapi.types.chat.Chat

private const val enableAutoDeleteCommands = "captcha_auto_delete_commands_on"
private const val disableAutoDeleteCommands = "captcha_auto_delete_commands_off"
private const val enableAutoDeleteServiceMessages = "captcha_auto_delete_events_on"
private const val disableAutoDeleteServiceMessages = "captcha_auto_delete_events_off"

private const val enableSlotMachineCaptcha = "captcha_use_slot_machine"
private const val enableSimpleCaptcha = "captcha_use_simple"
private const val enableExpressionCaptcha = "captcha_use_expression"
private const val disableCaptcha = "disable_captcha"
private const val enableCaptcha = "enable_captcha"

private val enableDisableKickOnUnsuccess = Regex("captcha_(enable|disable)_kick")
private const val enableKickOnUnsuccess = "captcha_enable_kick"
private const val disableKickOnUnsuccess = "captcha_disable_kick"

private val changeCaptchaMethodCommandRegex = Regex(
    "captcha_use_((slot_machine)|(simple)|(expression))"
)

@Serializable
class CaptchaBotPlugin : Plugin {
//    override suspend fun getCommands(): List<BotCommand> = listOf(
//        BotCommand(
//            enableAutoDeleteCommands,
//            "Enable auto removing of commands addressed to captcha plugin"
//        ),
//        BotCommand(
//            disableAutoDeleteCommands,
//            "Disable auto removing of commands addressed to captcha plugin"
//        ),
//        BotCommand(
//            enableAutoDeleteServiceMessages,
//            "Enable auto removing of users joined messages"
//        ),
//        BotCommand(
//            disableAutoDeleteServiceMessages,
//            "Disable auto removing of users joined messages"
//        ),
//        BotCommand(
//            enableSlotMachineCaptcha,
//            "Change captcha method to slot machine"
//        ),
//        BotCommand(
//            enableSimpleCaptcha,
//            "Change captcha method to simple button"
//        ),
//        BotCommand(
//            disableCaptcha,
//            "Disable captcha for chat"
//        ),
//        BotCommand(
//            enableCaptcha,
//            "Enable captcha for chat"
//        ),
//        BotCommand(
//            enableExpressionCaptcha,
//            "Change captcha method to expressions"
//        ),
//        BotCommand(
//            enableKickOnUnsuccess,
//            "Not solved captcha users will be kicked from the chat"
//        ),
//        BotCommand(
//            disableKickOnUnsuccess,
//            "Not solved captcha users will NOT be kicked from the chat"
//        )
//    )

    override fun Module.setupDI(database: Database, params: JsonObject) {
        single { CaptchaChatsSettingsRepo(database) }
    }

    override suspend fun BehaviourContext.setupBotPlugin(koin: Koin) {
        val repo: CaptchaChatsSettingsRepo by koin.inject()
        val adminsAPI = koin.adminsPlugin ?.adminsAPI(koin.get())

        suspend fun Chat.settings() = repo.getById(id) ?: repo.create(ChatSettings(id)).first()

        onNewChatMembers(
            initialFilter = {
                it.chat.asPublicChat() != null
            },
            subcontextUpdatesFilter = { m, u -> u.sourceChat() == m.chat },
        ) {
            launchSafelyWithoutExceptions {
                val settings = it.chat.settings()
                if (!settings.enabled) return@launchSafelyWithoutExceptions

                safelyWithoutExceptions {
                    if (settings.autoRemoveEvents) {
                        deleteMessage(it)
                    }
                }
                val chat = it.chat.requireGroupChat()
                val newUsers = it.chatEvent.members
                newUsers.forEach { user ->
                    restrictChatMember(
                        chat,
                        user,
                        permissions = RestrictionsChatPermissions
                    )
                }
                val defaultChatPermissions = (getChat(it.chat) as ExtendedGroupChat).permissions

                doInSubContext(stopOnCompletion = false) {
                    launch {
                        settings.captchaProvider.apply { doAction(it.date, chat, newUsers, defaultChatPermissions) }
                    }
                }
            }
        }

        if (adminsAPI != null) {
            onCommand(changeCaptchaMethodCommandRegex) {
                it.doAfterVerification(adminsAPI) {
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
                        else -> return@doAfterVerification
                    }
                    val newSettings = settings.copy(captchaProvider = captcha)
                    if (repo.contains(it.chat.id)) {
                        repo.update(it.chat.id, newSettings)
                    } else {
                        repo.create(newSettings)
                    }
                    sendMessage(it.chat, "Settings updated").also { sent ->
                        delay(5000L)

                        if (settings.autoRemoveCommands) {
                            deleteMessage(sent)
                        }
                    }
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

            onCommand(disableCaptcha) { message ->
                message.doAfterVerification(adminsAPI) {
                    val settings = message.chat.settings()

                    repo.update(
                        message.chat.id,
                        settings.copy(enabled = false)
                    )

                    reply(message, "Captcha has been disabled")

                    if (settings.autoRemoveCommands) {
                        deleteMessage(message)
                    }
                }
            }

            onCommand(enableCaptcha) { message ->
                message.doAfterVerification(adminsAPI) {
                    val settings = message.chat.settings()

                    repo.update(
                        message.chat.id,
                        settings.copy(enabled = true)
                    )

                    reply(message, "Captcha has been enabled")

                    if (settings.autoRemoveCommands) {
                        deleteMessage(message)
                    }
                }
            }

            onCommand(enableAutoDeleteServiceMessages) { message ->
                message.doAfterVerification(adminsAPI) {
                    val settings = message.chat.settings()

                    repo.update(
                        message.chat.id,
                        settings.copy(autoRemoveEvents = true)
                    )

                    reply(message, "Ok, user joined service messages will be deleted")

                    if (settings.autoRemoveCommands) {
                        deleteMessage(message)
                    }
                }
            }

            onCommand(disableAutoDeleteServiceMessages) { message ->
                message.doAfterVerification(adminsAPI) {
                    val settings = message.chat.settings()

                    repo.update(
                        message.chat.id,
                        settings.copy(autoRemoveEvents = false)
                    )

                    reply(message, "Ok, user joined service messages will not be deleted")

                    if (settings.autoRemoveCommands) {
                        deleteMessage(message)
                    }
                }
            }
        }
    }
}
