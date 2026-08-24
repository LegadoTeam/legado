package io.legado.app.data.entities

data class ReadRecordShow(
    var bookName: String,
    var readTime: Long,
    var lastRead: Long,
    /** Raw author values aggregated across devices; empty for legacy records. */
    var author: String = ""
) {
    val displayAuthor: String
        get() = ReadRecordAuthors.display(author)
}
