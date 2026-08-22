package io.legado.app.help.book

import io.legado.app.data.entities.Book

/**
 * Search/explore pass a URL that must keep the detail page. Official-on-shelf
 * is this URL only.
 */
internal object BookInfoOpenResolver {

    data class Result(
        val book: Book,
        val inBookshelf: Boolean,
    )

    fun resolve(
        bookUrl: String,
        shelfByUrl: Book?,
        searchByUrl: Book?,
        shelfByNameAuthor: Book?,
        searchByNameAuthor: Book?,
    ): Result? {
        if (bookUrl.isNotBlank()) {
            shelfByUrl?.let {
                return Result(it, inBookshelf = !it.isNotShelf)
            }
            searchByUrl?.let { return Result(it, inBookshelf = false) }
            return null
        }
        shelfByNameAuthor?.let {
            return Result(it, inBookshelf = !it.isNotShelf)
        }
        searchByNameAuthor?.let { return Result(it, inBookshelf = false) }
        return null
    }
}
