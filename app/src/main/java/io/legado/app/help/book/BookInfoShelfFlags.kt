package io.legado.app.help.book

/**
 * BookInfo shelf flags.
 *
 * [State.inBookshelf] is identity-on-shelf (badge / skip-insert / UI).
 * [State.urlOnShelf] is a visible (non-notShelf) `books` row for this URL.
 *
 * Temp persist for TOC/Read only proves `getBook(url)` can succeed. It must
 * not promote either flag. Official add and DB presence own the flags.
 */
internal object BookInfoShelfFlags {

    data class State(
        val inBookshelf: Boolean,
        val urlOnShelf: Boolean,
    )

    fun fromPresence(identityOnShelf: Boolean, urlOnShelf: Boolean): State {
        return State(inBookshelf = identityOnShelf, urlOnShelf = urlOnShelf)
    }

    /** URL is now in DB so TOC/Read can load it. Do not mark official shelf. */
    fun afterUrlPersisted(previous: State): State = previous

    fun afterReaderReturned(identityOnShelf: Boolean, urlOnShelf: Boolean): State {
        return fromPresence(identityOnShelf, urlOnShelf)
    }

    fun afterBookRestored(identityOnShelf: Boolean, urlOnShelf: Boolean): State {
        return fromPresence(identityOnShelf, urlOnShelf)
    }

    /**
     * Delete only the current page URL. Identity-only must not delete the
     * other shelf row.
     */
    fun canDeleteBookUrl(pageUrl: String, persistedUrl: String?): Boolean {
        return pageUrl.isNotBlank() && persistedUrl == pageUrl
    }

    fun readerInBookshelfExtra(urlOnShelf: Boolean): Boolean = urlOnShelf
}
