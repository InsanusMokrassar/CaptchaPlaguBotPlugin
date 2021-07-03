package dev.inmo.plagubot.plugins.captcha.settings

import dev.inmo.plagubot.plugins.captcha.provider.CaptchaProvider
import dev.inmo.plagubot.plugins.captcha.provider.SimpleCaptchaProvider
import dev.inmo.tgbotapi.types.ChatId
import kotlinx.serialization.Serializable

@Serializable
data class ChatSettings(
    val chatId: ChatId,
    val captchaProvider: CaptchaProvider = SimpleCaptchaProvider(),
    val autoRemoveCommands: Boolean = false
)
