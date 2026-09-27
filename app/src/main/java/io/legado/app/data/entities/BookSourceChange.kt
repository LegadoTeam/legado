package io.legado.app.data.entities

import io.legado.app.data.appDb
import io.legado.app.model.ReadBook

/** Commit the new book, its chapters and memo together after source loading has succeeded. */
fun replaceBookAfterSourceChange(
    oldBook: Book?, newBook: Book, chapters: List<BookChapter>, clearActiveReader: Boolean = true,
) {
    oldBook?.saveReadRecordSnapshot()
    appDb.runInTransaction {
        if (oldBook == null) appDb.bookDao.insert(newBook)
        else appDb.bookDao.replace(oldBook, newBook)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
    }
    if (clearActiveReader && oldBook != null && ReadBook.book?.bookUrl == oldBook.bookUrl) ReadBook.book = null
}
