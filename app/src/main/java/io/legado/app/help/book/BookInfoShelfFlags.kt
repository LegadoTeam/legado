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
     * After migrateTo onto an existing official URL, reuse [Book.updateTo] for
     * shelf user fields, then put back the migrate reading progress.
     */
    fun restoreOfficialUserFields(incoming: Book, existing: Book?) {
        existing ?: return
        if (existing.isNotShelf) return
        val durChapterIndex = incoming.durChapterIndex
        val durChapterTitle = incoming.durChapterTitle
        val durVolumeIndex = incoming.durVolumeIndex
        val chapterInVolumeIndex = incoming.chapterInVolumeIndex
        val durChapterPos = incoming.durChapterPos
        val durChapterTime = incoming.durChapterTime
        existing.updateTo(incoming)
        incoming.durChapterIndex = durChapterIndex
        incoming.durChapterTitle = durChapterTitle
        incoming.durVolumeIndex = durVolumeIndex
        incoming.chapterInVolumeIndex = chapterInVolumeIndex
        incoming.durChapterPos = durChapterPos
        incoming.durChapterTime = durChapterTime
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
