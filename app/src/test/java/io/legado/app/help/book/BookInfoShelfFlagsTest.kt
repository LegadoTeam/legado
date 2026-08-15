package io.legado.app.help.book

import io.legado.app.data.entities.Book
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BookInfoShelfFlagsTest {

    @Test
    fun tocCancelDeletesOnlyTheCurrentNotShelfUrl() {
        assertTrue(BookInfoShelfFlags.canDeleteBookUrl("B", "B"))
        assertTrue(BookInfoShelfFlags.canDeleteTempBookUrl("B", "B", persistedIsNotShelf = true))
        assertFalse(BookInfoShelfFlags.canDeleteTempBookUrl("B", "B", persistedIsNotShelf = false))
        assertFalse(BookInfoShelfFlags.canDeleteTempBookUrl("B", "A", persistedIsNotShelf = true))
        assertFalse(BookInfoShelfFlags.canDeleteBookUrl("B", "A"))
    }

    @Test
    fun readerReturnPrefersCurrentPageThenIntentThenNameAuthor() {
        val current = Book(bookUrl = "B", name = "T", author = "甲")
        val extra = Book(bookUrl = "A", name = "T", author = "甲")
        assertSame(
            current,
            BookInfoShelfFlags.resolveReturnBook("B", "A", current, extra, extra),
        )
        assertSame(
            extra,
            BookInfoShelfFlags.resolveReturnBook("B", "A", null, extra, extra),
        )
        assertSame(
            extra,
            BookInfoShelfFlags.resolveReturnBook("B", "A", null, null, extra),
        )
        assertNull(BookInfoShelfFlags.resolveReturnBook("B", "A", null, null, null))
    }

    @Test
    fun readerExtraIsOfficialUrlOnly() {
        assertFalse(BookInfoShelfFlags.readerInBookshelfExtra(false))
        assertTrue(BookInfoShelfFlags.readerInBookshelfExtra(true))
    }
}
