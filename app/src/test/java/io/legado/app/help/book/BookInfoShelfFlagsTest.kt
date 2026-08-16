package io.legado.app.help.book

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
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
