package io.legado.app.data.entities

data class ReadRecordShow(
    var bookName: String,
    var readTime: Long,
    var lastRead: Long,
    /** Raw author values aggregated across devices; empty for legacy records. */
    var author: String = "",
    var lastChapterTitle: String? = null,
    var lastChapterIndex: Int = -1,
    var lastChapterPos: Int = 0,
    var coverUrl: String? = null,
) {
    val displayAuthor: String
        get() = ReadRecordAuthors.display(author)
}
