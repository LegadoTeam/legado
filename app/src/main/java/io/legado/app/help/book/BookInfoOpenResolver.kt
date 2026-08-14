package io.legado.app.help.book

import io.legado.app.data.entities.Book

/**
 * Search/explore pass a URL that must keep the detail page. Do not open a
 * different shelf row just because raw (name, author) matches.
 */
internal object BookInfoOpenResolver {

    data class Result(
        val book: Book,
        val identityOnShelf: Boolean,
        val urlOnShelf: Boolean,
    )

    fun resolve(
        name: String,
        author: String,
        bookUrl: String,
        shelfByUrl: Book?,
        searchByUrl: Book?,
        shelfByNameAuthor: Book?,
        searchByNameAuthor: Book?,
        presence: SearchBookShelfHelp.ShelfPresence,
    ): Result? {
        if (bookUrl.isNotBlank()) {
            shelfByUrl?.let {
                val on = !it.isNotShelf
                return Result(it, identityOnShelf = on, urlOnShelf = on)
            }
            searchByUrl?.let {
                return Result(
                    it,
                    identityOnShelf = presence.identityOnShelf,
                    urlOnShelf = false,
                )
            }
            return null
        }
        shelfByNameAuthor?.let {
            val on = !it.isNotShelf
            return Result(it, identityOnShelf = on, urlOnShelf = on)
        }
        searchByNameAuthor?.let {
            return Result(
                it,
                identityOnShelf = presence.identityOnShelf,
                urlOnShelf = false,
            )
        }
        return null
    }
}
