package io.legado.app.help.book

import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book

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
     * Reader/TOC return: follow the page's current URL (changeTo may have
     * replaced the book). The launch Intent extra can still be the old URL.
     */
    fun resolveReturnBookUrl(currentPageUrl: String?, intentUrl: String?): String {
        return currentPageUrl?.takeIf { it.isNotBlank() } ?: intentUrl.orEmpty()
    }

    /**
     * User remove-from-shelf: delete only the current page URL.
     * Identity-only must not delete the other shelf row.
     */
    fun canDeleteBookUrl(pageUrl: String, persistedUrl: String?): Boolean {
        return pageUrl.isNotBlank() && persistedUrl == pageUrl
    }

    /**
     * TOC/reader leftover cleanup. Official rows must survive a stale
     * [urlOnShelf] after changeTo.
     */
    fun canDeleteTempBookUrl(
        pageUrl: String,
        persistedUrl: String?,
        persistedIsNotShelf: Boolean,
    ): Boolean {
        return canDeleteBookUrl(pageUrl, persistedUrl) && persistedIsNotShelf
    }

    fun readerInBookshelfExtra(urlOnShelf: Boolean): Boolean = urlOnShelf

    /**
     * Promote a temp URL only when this URL is already official, or no
     * visible shelf identity owns the name. Weak leftover rows must not
     * become a second official book beside the sole real author.
     */
    fun canPromoteToOfficial(identityOnShelf: Boolean, urlOnShelf: Boolean): Boolean {
        return urlOnShelf || !identityOnShelf
    }

    fun promoteOrSkipTempBook(book: Book): Boolean {
        val presence = SearchBookShelfHelp.presence(book.name, book.author, book.bookUrl)
        if (!canPromoteToOfficial(presence.identityOnShelf, presence.urlOnShelf)) {
            appDb.bookDao.getBook(book.bookUrl)?.takeIf { it.isNotShelf }?.delete()
            return false
        }
        book.removeType(BookType.notShelf)
        book.save()
        return true
    }
}
