package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook

/**
 * Search/explore pass a URL that must keep the detail page. Do not open a
 * different shelf row just because raw (name, author) matches.
 */
internal object BookInfoOpenResolver {

    data class Result(
        val book: Book,
        val inBookshelf: Boolean,
    )

    fun resolve(
        name: String,
        author: String,
        bookUrl: String,
        shelfByUrl: Book?,
        searchByUrl: Book?,
        shelfByNameAuthor: Book?,
        searchByNameAuthor: Book?,
        incomingOnShelf: Boolean,
    ): Result? {
        if (bookUrl.isNotBlank()) {
            shelfByUrl?.let { return Result(it, !it.isNotShelf) }
            searchByUrl?.let { return Result(it, incomingOnShelf) }
            return null
        }
        shelfByNameAuthor?.let { return Result(it, !it.isNotShelf) }
        searchByNameAuthor?.let { return Result(it, incomingOnShelf) }
        return null
    }
}
