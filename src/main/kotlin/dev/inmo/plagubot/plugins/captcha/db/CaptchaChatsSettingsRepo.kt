package dev.inmo.plagubot.plugins.captcha.db

import dev.inmo.micro_utils.repos.exposed.*
import dev.inmo.micro_utils.repos.exposed.keyvalue.ExposedKeyValueRepo
import dev.inmo.plagubot.plugins.captcha.settings.*
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.toChatId
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.InsertStatement
import org.jetbrains.exposed.sql.statements.UpdateStatement

class CaptchaChatsSettingsRepo(
    override val database: Database
) : AbstractExposedCRUDRepo<ChatSettings, ChatId, ChatSettings>(
    tableName = "CaptchaChatsSettingsRepo"
) {
    private val chatIdColumn = long("chatId")
    private val checkTimeSecondsColumn = integer("checkTime")
    private val solveCaptchaTextColumn = text("solveCaptchaText")
    private val autoRemoveCommandsColumn = bool("autoRemoveCommands")

    override val primaryKey = PrimaryKey(chatIdColumn)

    override val selectByIds: SqlExpressionBuilder.(List<ChatId>) -> Op<Boolean> = {
        chatIdColumn.inList(it.map { it.chatId })
    }
    override val InsertStatement<Number>.asObject: ChatSettings
        get() = TODO("Not yet implemented")

    override fun insert(value: ChatSettings, it: InsertStatement<Number>) {
        it[chatIdColumn] = value.chatId.chatId
        it[checkTimeSecondsColumn] = value.checkTime
        it[solveCaptchaTextColumn] = value.captchaText
        it[autoRemoveCommandsColumn] = value.autoRemoveCommands
    }

    override fun update(id: ChatId, value: ChatSettings, it: UpdateStatement) {
        if (id.chatId == value.chatId.chatId) {
            it[checkTimeSecondsColumn] = value.checkTime
            it[solveCaptchaTextColumn] = value.captchaText
            it[autoRemoveCommandsColumn] = value.autoRemoveCommands
        }
    }

    override fun InsertStatement<Number>.asObject(value: ChatSettings): ChatSettings = ChatSettings(
        get(chatIdColumn).toChatId(),
        get(checkTimeSecondsColumn),
        get(solveCaptchaTextColumn),
        get(autoRemoveCommandsColumn)
    )

    override val selectById: SqlExpressionBuilder.(ChatId) -> Op<Boolean> = { chatIdColumn.eq(it.chatId) }
    override val ResultRow.asObject: ChatSettings
        get() = ChatSettings(
            get(chatIdColumn).toChatId(),
            get(checkTimeSecondsColumn),
            get(solveCaptchaTextColumn),
            get(autoRemoveCommandsColumn)
        )

    init {
        initTable()
    }
}
