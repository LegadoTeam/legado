package io.legado.app.model

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookHighlight
import io.legado.app.ui.book.read.page.entities.TextChapter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadBookHighlightIsolationTest {

    private val book = Book(bookUrl = "book-url", name = "book", author = "author")

    @Test
    fun `highlight belongs only to the matching current book`() {
        assertTrue(BookHighlight(bookUrl = "book-url").isForBook(book))
        assertFalse(BookHighlight(bookUrl = "other-url").isForBook(book))
        assertFalse(BookHighlight(bookUrl = "book-url").isForBook(null))
    }

    @Test
    fun `highlight follows chapter url instead of mutable directory index`() {
        val chapter = BookChapter(bookUrl = "book-url", url = "chapter-url", index = 8)
        val highlight = BookHighlight(
            bookUrl = "book-url",
            chapterUrl = "chapter-url",
            chapterIndex = 2
        )

        assertTrue(highlight.isForChapter(book, chapter))
        assertFalse(highlight.isForChapter(book, chapter.copy(url = "other-chapter")))
        assertFalse(highlight.isForChapter(Book(bookUrl = "other-url"), chapter))
    }

    @Test
    fun `legacy highlight binds once when chapter metadata matches`() {
        val chapter = BookChapter(
            bookUrl = "book-url",
            url = "chapter-url",
            index = 2,
            title = "raw chapter"
        )
        val highlight = BookHighlight(
            bookUrl = "book-url",
            chapterIndex = 2,
            chapterName = "display chapter"
        )

        assertTrue(highlight.bindLegacyChapter(book, chapter, "display chapter"))
        assertTrue(highlight.isForChapter(book, chapter))
        assertFalse(highlight.bindLegacyChapter(book, chapter.copy(url = "other-chapter")))
        assertFalse(
            BookHighlight(
                bookUrl = "book-url",
                chapterIndex = 2,
                chapterName = "other"
            ).bindLegacyChapter(book, chapter)
        )
    }

    @Test
    fun `laid out chapter belongs only to the matching book url`() {
        val chapter = TextChapter(
            chapter = BookChapter(bookUrl = "book-url"),
            position = 0,
            title = "chapter",
            chaptersSize = 1,
            sameTitleRemoved = false,
            isVip = false,
            isPay = false,
            effectiveReplaceRules = null
        )

        assertTrue(chapter.isForBook(Book(bookUrl = "book-url")))
        assertFalse(chapter.isForBook(Book(bookUrl = "other-url")))
        assertFalse(chapter.isForBook(null))
    }
}
