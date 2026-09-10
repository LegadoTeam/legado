package io.legado.app.ui.book.import.local

import io.legado.app.utils.FileDoc
import io.legado.app.data.entities.Book

data class ImportBook(
    val file: FileDoc,
    var isOnBookShelf: Boolean,
    val preview: Book? = null,
) {
    val name get() = file.name
    val isDir get() = file.isDir
    val size get() = file.size
    val lastModified get() = file.lastModified
}

class ImportBookShelfFiles(
    fileNames: Iterable<String>,
    alternateOrigins: Iterable<String>
) {
    private val fileNames = fileNames.toHashSet()
    private val alternateOrigins = alternateOrigins.toHashSet()

    operator fun contains(fileName: String): Boolean {
        return fileName in fileNames || alternateOrigins.any {
            it.endsWith(fileName, ignoreCase = true)
        }
    }
}
