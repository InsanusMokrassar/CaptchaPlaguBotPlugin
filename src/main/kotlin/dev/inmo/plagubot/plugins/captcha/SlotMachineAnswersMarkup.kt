package dev.inmo.plagubot.plugins.captcha

import dev.inmo.tgbotapi.extensions.utils.SlotMachineReelImage
import dev.inmo.tgbotapi.extensions.utils.types.buttons.*
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup

infix fun String.startingOf(target: String) = target.startsWith(this)

fun slotMachineReplyMarkup(
    first: String? = null,
    second: String? = null,
    third: String? = null,
): InlineKeyboardMarkup {
    val texts = when {
        first == null -> SlotMachineReelImage.values().map {
            CallbackDataInlineKeyboardButton("${it.text}**", it.text)
        }
        second == null -> SlotMachineReelImage.values().map {
            CallbackDataInlineKeyboardButton("$first${it.text}*", it.text)
        }
        third == null -> SlotMachineReelImage.values().map {
            CallbackDataInlineKeyboardButton("$first$second${it.text}", it.text)
        }
        else -> listOf(CallbackDataInlineKeyboardButton("$first$second$third", "$first$second$third"))
    }
    return inlineKeyboard {
        texts.chunked(2).forEach { add(it) }
//        row {
//            dataButton("Cancel (Admins only)", "cancel")
//        }
    }
}
