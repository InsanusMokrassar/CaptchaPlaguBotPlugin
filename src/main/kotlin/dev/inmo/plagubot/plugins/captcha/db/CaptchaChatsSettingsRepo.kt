package dev.inmo.plagubot.plugins.captcha.db

import dev.inmo.micro_utils.repos.exposed.*
import dev.inmo.plagubot.plugins.captcha.provider.CaptchaProvider
import dev.inmo.plagubot.plugins.captcha.settings.*
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.toChatId
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.InsertStatement
import org.jetbrains.exposed.sql.statements.UpdateStatement

private val captchaProviderSerialFormat = Json {
    ignoreUnknownKeys = true
}

class CaptchaChatsSettingsRepo(
    override val database: Database
) : AbstractExposedCRUDRepo<ChatSettings, ChatId, ChatSettings>(
    tableName = "CaptchaChatsSettingsRepo"
) {
    private val chatIdColumn = long("chatId")
    private val captchaProviderColumn = text("captchaProvider")
    private val autoRemoveCommandsColumn = bool("autoRemoveCommands")

    override val primaryKey = PrimaryKey(chatIdColumn)

    override val selectByIds: SqlExpressionBuilder.(List<ChatId>) -> Op<Boolean> = {
        chatIdColumn.inList(it.map { it.chatId })
    }
    override val InsertStatement<Number>.asObject: ChatSettings
        get() = TODO("Not yet implemented")

    override fun insert(value: ChatSettings, it: InsertStatement<Number>) {
        it[chatIdColumn] = value.chatId.chatId
        it[captchaProviderColumn] = captchaProviderSerialFormat.encodeToString(CaptchaProvider.serializer(), value.captchaProvider)
        it[autoRemoveCommandsColumn] = value.autoRemoveCommands
    }

    override fun update(id: ChatId, value: ChatSettings, it: UpdateStatement) {
        if (id.chatId == value.chatId.chatId) {
            it[captchaProviderColumn] = captchaProviderSerialFormat.encodeToString(CaptchaProvider.serializer(), value.captchaProvider)
            it[autoRemoveCommandsColumn] = value.autoRemoveCommands
        }
    }

    override fun InsertStatement<Number>.asObject(value: ChatSettings): ChatSettings = ChatSettings(
        get(chatIdColumn).toChatId(),
        captchaProviderSerialFormat.decodeFromString(CaptchaProvider.serializer(), get(captchaProviderColumn)),
        get(autoRemoveCommandsColumn)
    )

    override val selectById: SqlExpressionBuilder.(ChatId) -> Op<Boolean> = { chatIdColumn.eq(it.chatId) }
    override val ResultRow.asObject: ChatSettings
        get() = ChatSettings(
            get(chatIdColumn).toChatId(),
            captchaProviderSerialFormat.decodeFromString(CaptchaProvider.serializer(), get(captchaProviderColumn)),
            get(autoRemoveCommandsColumn)
        )

    init {
        initTable()
    }
}
