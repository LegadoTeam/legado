package io.legado.app.ui.main.bookshelf.style2

import io.legado.app.constant.BookType
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookshelfBook
import io.legado.app.utils.cnCompare
import kotlin.math.max

data class BookshelfGroupItem(
    val group: BookGroup,
    val previewBooks: List<BookshelfBook>,
) {
    val coverSignature: List<List<String?>> = previewBooks.map { book ->
        listOf(
            book.bookUrl,
            book.displayCover,
            book.name,
            book.author,
            book.coverSourceOrigin,
        )
    }
}

internal fun buildBookshelfGroupItems(
    groups: List<BookGroup>,
    books: List<BookshelfBook>,
): List<BookshelfGroupItem> {
    return groups.map { group ->
        val previewBooks = if (group.cover.isNullOrBlank()) {
            sortBookshelfBooks(
                books.filter { it.belongsToGroup(group.groupId) },
                group.getRealBookSort(),
            ).take(4)
        } else {
            emptyList()
        }
        BookshelfGroupItem(group, previewBooks)
    }
}

internal fun sortBookshelfBooks(books: List<BookshelfBook>, sort: Int): List<BookshelfBook> {
    return when (sort) {
        1 -> books.sortedByDescending { it.latestChapterTime }
        2 -> books.sortedWith { first, second -> first.name.cnCompare(second.name) }
        3 -> books.sortedBy { it.order }
        4 -> books.sortedByDescending { max(it.latestChapterTime, it.durChapterTime) }
        else -> books.sortedByDescending { it.durChapterTime }
    }
}

private fun BookshelfBook.belongsToGroup(groupId: Long): Boolean {
    val isAudio = type and BookType.audio > 0
    val isLocal = type and BookType.local > 0
    val isVideo = type and BookType.video > 0
    return when (groupId) {
        BookGroup.IdAll -> true
        BookGroup.IdLocal -> isLocal
        BookGroup.IdAudio -> isAudio
        BookGroup.IdNetNone -> !isLocal && !isAudio && !isVideo && !hasUserGroup
        BookGroup.IdLocalNone -> isLocal && !hasUserGroup
        BookGroup.IdVideo -> isVideo
        BookGroup.IdError -> type and BookType.updateError > 0
        else -> groupId > 0 && (group and groupId) > 0
    }
}
