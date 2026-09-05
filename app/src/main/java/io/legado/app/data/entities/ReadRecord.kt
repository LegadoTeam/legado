package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import io.legado.app.constant.AppConst
import io.legado.app.data.appDb
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
    var lastRead: Long = System.currentTimeMillis(),
    /** Snapshot fields keep the record useful after its bookshelf row is removed. */
    var lastChapterTitle: String? = null,
    @ColumnInfo(defaultValue = "-1")
    var lastChapterIndex: Int = -1,
    @ColumnInfo(defaultValue = "0")
    var lastChapterPos: Int = 0,
    var coverUrl: String? = null,
)

fun ReadRecord.updateSnapshot(
    book: Book,
    chapterIndex: Int = book.durChapterIndex,
    chapterPos: Int = book.durChapterPos,
) {
    lastChapterIndex = chapterIndex
    book.durChapterTitle?.takeIf { it.isNotBlank() }?.let { lastChapterTitle = it }
    lastChapterPos = chapterPos
    book.getDisplayCover()?.takeIf { it.isNotBlank() }?.let { coverUrl = it }
}

/** Copy the bookshelf data before deleting it so the history row remains displayable. */
fun Book.saveReadRecordSnapshot() {
    val current = appDb.readRecordDao.getRecord(AppConst.androidId, name)
    if (current == null && durChapterIndex == 0 && durChapterPos == 0 &&
        durChapterTitle.isNullOrBlank()
    ) {
        return
    }
    val record = (current ?: ReadRecord(deviceId = AppConst.androidId, bookName = name))
        .copy(
            deviceId = AppConst.androidId,
            bookName = name,
            author = author.ifBlank { current?.author.orEmpty() },
            lastChapterTitle = durChapterTitle ?: current?.lastChapterTitle,
            lastChapterIndex = durChapterIndex,
            lastChapterPos = durChapterPos,
            coverUrl = getDisplayCover() ?: current?.coverUrl,
        )
    appDb.readRecordDao.insert(record)
}

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
