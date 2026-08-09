package io.legado.app.ui.book.import.local

import io.legado.app.utils.FileDoc

data class ImportBook(
    val file: FileDoc,
    var isOnBookShelf: Boolean
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
