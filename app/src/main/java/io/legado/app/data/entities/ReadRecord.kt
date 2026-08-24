package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray

@Entity(tableName = "readRecord", primaryKeys = ["deviceId", "bookName"])
data class ReadRecord(
    var deviceId: String = "",
    var bookName: String = "",
    /**
     * 书名相同的书籍共用一条记录,作者只用于辅助判断书籍身份,旧记录和旧备份为空
     */
    @ColumnInfo(defaultValue = "")
    var author: String = "",
    @ColumnInfo(defaultValue = "0")
    var readTime: Long = 0L,
    @ColumnInfo(defaultValue = "0")
    var lastRead: Long = System.currentTimeMillis()
)

/** 同设备同书名共用主键,复用 author 列保存作者集合,纯文本仍兼容旧记录. */
internal object ReadRecordAuthors {
    private const val PREFIX = "\u001Eauthors:"
    const val AGGREGATE_SEPARATOR = "\u001F"

    fun decode(value: String): Set<String> {
        if (value.isBlank()) return setOf("")
        if (!value.startsWith(PREFIX)) return setOf(value)
        return GSON.fromJsonArray<String>(value.removePrefix(PREFIX))
            .getOrNull()
            ?.filterTo(linkedSetOf()) { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: setOf("")
    }

    fun merge(current: String, incoming: String): String {
        val authors = sortedSetOf<String>()
        decode(current).filterTo(authors) { it.isNotBlank() }
        decode(incoming).filterTo(authors) { it.isNotBlank() }
        return when (authors.size) {
            0 -> ""
            1 -> authors.first()
            else -> PREFIX + GSON.toJsonTree(authors).toString()
        }
    }

    /** Converts DAO aggregate values into a stable, human-readable author list. */
    fun display(value: String): String {
        if (value.isBlank()) return ""
        return value.split(AGGREGATE_SEPARATOR)
            .flatMap(::decode)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
            .joinToString("、")
    }
}
