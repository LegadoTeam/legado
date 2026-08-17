package io.legado.app.help.book

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book

/**
 * BookInfo official-shelf flag is this URL only. Temp TOC/Read persist must
 * not mark official. Weak add/promote is skipped when a sole web real exists.
 */
internal object BookInfoShelfFlags {

    fun isOfficialUrl(bookUrl: String): Boolean {
        return SearchBookShelfHelp.isOfficialUrlOnShelf(bookUrl)
    }

    fun canDeleteBookUrl(pageUrl: String, persistedUrl: String?): Boolean {
        return pageUrl.isNotBlank() && persistedUrl == pageUrl
    }

    /** Do not let a caller mark an official row as temporary. */
    fun keepExistingNotShelf(incoming: Book, existing: Book) {
        if (existing.isNotShelf) {
            incoming.addType(BookType.notShelf)
        } else {
            incoming.removeType(BookType.notShelf)
        }
    }

    /**
     * After migrateTo onto an existing official URL, keep that row's user
     * fields (group / custom* / readConfig). Chapter progress may stay from
     * migrateTo.
     */
    fun restoreOfficialUserFields(incoming: Book, existing: Book?) {
        existing ?: return
        if (existing.isNotShelf) return
        incoming.group = existing.group
        incoming.order = existing.order
        incoming.customCoverUrl = existing.customCoverUrl
        incoming.customIntro = existing.customIntro
        incoming.customTag = existing.customTag
        incoming.canUpdate = existing.canUpdate
        incoming.readConfig = existing.readConfig
    }

    /** Before save: official rows keep DB user fields; temp rows keep progress. */
    fun applyExistingBeforeSave(incoming: Book, existing: Book) {
        if (existing.isNotShelf) {
            incoming.durChapterIndex = existing.durChapterIndex
            incoming.durChapterPos = existing.durChapterPos
            incoming.durChapterTitle = existing.durChapterTitle
        } else {
            existing.updateTo(incoming)
        }
        keepExistingNotShelf(incoming, existing)
    }

    fun promoteOrSkipTempBook(book: Book): Boolean {
        if (SearchBookShelfHelp.shouldSkipWeakInsert(book.name, book.author, book.bookUrl)) {
            return false
        }
        book.removeType(BookType.notShelf)
        book.save()
        return true
    }
}
