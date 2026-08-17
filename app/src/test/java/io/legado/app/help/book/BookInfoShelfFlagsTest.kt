package io.legado.app.help.book

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookInfoShelfFlagsTest {

    @Test
    fun tocCancelDeletesOnlyTheCurrentNotShelfUrl() {
        assertTrue(BookInfoShelfFlags.canDeleteBookUrl("B", "B"))
        assertFalse(BookInfoShelfFlags.canDeleteBookUrl("B", "A"))
    }

    @Test
    fun officialRowDoesNotKeepCallerNotShelf() {
        val existing = Book(bookUrl = "A", name = "T", author = "甲")
        val incoming = Book(bookUrl = "A", name = "T", author = "甲", type = BookType.notShelf)
        BookInfoShelfFlags.keepExistingNotShelf(incoming, existing)
        assertFalse(incoming.isNotShelf)
    }

    @Test
    fun restoreOfficialUserFieldsKeepsGroupAndCustomIntro() {
        val existing = Book(
            bookUrl = "A",
            name = "T",
            author = "甲",
            group = 9L,
            order = -3,
            customIntro = "keep-intro",
            customTag = "tag-a",
        )
        val incoming = Book(
            bookUrl = "A",
            name = "T",
            author = "甲",
            group = 0L,
            order = 0,
            customIntro = "from-B",
            customTag = "tag-b",
            durChapterPos = 42,
        )
        BookInfoShelfFlags.restoreOfficialUserFields(incoming, existing)
        assertEquals(9L, incoming.group)
        assertEquals(-3, incoming.order)
        assertEquals("keep-intro", incoming.customIntro)
        assertEquals("tag-a", incoming.customTag)
        assertEquals(42, incoming.durChapterPos)
    }

    @Test
    fun applyExistingBeforeSaveUsesUpdateToForOfficialRow() {
        val existing = Book(
            bookUrl = "A",
            name = "T",
            author = "甲",
            group = 7L,
            customIntro = "shelf-intro",
            durChapterIndex = 3,
            durChapterPos = 11,
        )
        val incoming = Book(
            bookUrl = "A",
            name = "T",
            author = "甲",
            type = BookType.notShelf,
            group = 0L,
            customIntro = "from-temp",
            durChapterIndex = 0,
            durChapterPos = 0,
        )
        BookInfoShelfFlags.applyExistingBeforeSave(incoming, existing)
        assertFalse(incoming.isNotShelf)
        assertEquals(7L, incoming.group)
        assertEquals("shelf-intro", incoming.customIntro)
        assertEquals(3, incoming.durChapterIndex)
        assertEquals(11, incoming.durChapterPos)
    }

    @Test
    fun temporaryRowKeepsNotShelf() {
        val existing = Book(
            bookUrl = "A",
            name = "T",
            author = "甲",
            type = BookType.text or BookType.notShelf,
        )
        val incoming = Book(bookUrl = "A", name = "T", author = "甲")
        BookInfoShelfFlags.keepExistingNotShelf(incoming, existing)
        assertTrue(incoming.isNotShelf)
    }
}
