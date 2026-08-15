package io.legado.app.help.book

import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book

/**
 * BookInfo official-shelf flag is this URL only. Temp TOC/Read persist must
 * not mark official. Weak add/promote is skipped when a sole web real exists.
 */
internal object BookInfoShelfFlags {

    fun isOfficialUrl(bookUrl: String): Boolean {
        return SearchBookShelfHelp.isOfficialUrlOnShelf(bookUrl)
    }

    fun resolveReturnBook(
        currentPageUrl: String?,
        intentUrl: String?,
        byCurrentUrl: Book?,
        byIntentUrl: Book?,
        byNameAuthor: Book?,
    ): Book? {
        if (!currentPageUrl.isNullOrBlank() && byCurrentUrl != null) return byCurrentUrl
        if (!intentUrl.isNullOrBlank() && byIntentUrl != null) return byIntentUrl
        return byNameAuthor
    }

    fun canDeleteBookUrl(pageUrl: String, persistedUrl: String?): Boolean {
        return pageUrl.isNotBlank() && persistedUrl == pageUrl
    }

    fun readerInBookshelfExtra(officialUrlOnShelf: Boolean): Boolean = officialUrlOnShelf

    fun promoteOrSkipTempBook(book: Book): Boolean {
        if (SearchBookShelfHelp.shouldSkipWeakInsert(book.name, book.author, book.bookUrl)) {
            return false
        }
        book.removeType(BookType.notShelf)
        book.save()
        return true
    }
}
