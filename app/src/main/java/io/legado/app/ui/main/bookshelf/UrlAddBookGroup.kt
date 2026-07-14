package io.legado.app.ui.main.bookshelf

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter

internal fun mergeBookGroupForUrlAdd(
    currentGroupMask: Long,
    selectedShelfGroupId: Long,
): Long {
    return if (selectedShelfGroupId > 0L) {
        currentGroupMask or selectedShelfGroupId
    } else {
        currentGroupMask
    }
}

internal fun migrateBookForUrlAdd(
    existingBook: Book,
    fetchedBook: Book,
    toc: List<BookChapter>,
    selectedShelfGroupId: Long,
): Book {
    return existingBook.migrateTo(fetchedBook, toc).apply {
        group = mergeBookGroupForUrlAdd(group, selectedShelfGroupId)
    }
}
