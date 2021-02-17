package dev.inmo.plagubot.plugins.captcha.settings

import com.soywiz.klock.TimeSpan
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.Seconds
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class ChatSettings(
    val chatId: ChatId,
    val checkTime: Seconds = 60,
    val captchaText: String = "solve next captcha:"
) {
    @Transient
    val checkTimeSpan = TimeSpan(checkTime * 1000.0)
}
