package io.legado.app.help.book

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchBookShelfHelpTest {

    @Test
    fun emptyListDoesNotTouchStore() {
        val store = FakeStore(minOrder = 8)

        val result = SearchBookShelfHelp.addLoadedBooksToShelf(emptyList(), store)

        assertEquals(0, result.total)
        assertEquals(0, result.added)
        assertEquals(0, store.insertAttempts)
        assertEquals(0, store.updateCount)
    }

    @Test
    fun newBooksUseStableOrderAndBatchDuplicatesAreSkipped() {
        val store = FakeStore(minOrder = 10)
        val result = SearchBookShelfHelp.addLoadedBooksToShelf(
            listOf(
                searchBook("url-1", "Book A", "Author A"),
                searchBook("url-1", "Book A", "Author A"),
                searchBook("url-2", "Book A", "Author A"),
                searchBook("url-3", "Book B", "Author B"),
            ),
            store,
        )

        assertEquals(4, result.total)
        assertEquals(2, result.added)
        assertEquals(2, result.skipped)
        assertEquals(listOf("url-1", "url-3"), result.addedBooks.map { it.bookUrl })
        assertEquals(listOf(8, 9), result.addedBooks.map { it.order })
    }

    @Test
    fun existingShelfBookIsNotReplacedByAnotherUrl() {
        val existing = Book(
            bookUrl = "saved-url",
            name = "Saved Book",
            author = "Saved Author",
            order = 7,
            group = 4,
            customCoverUrl = "saved-cover",
        )
        val store = FakeStore(existing)

        val result = SearchBookShelfHelp.addLoadedBooksToShelf(
            listOf(searchBook("new-url", existing.name, existing.author)),
            store,
        )

        assertEquals(0, result.added)
        assertEquals(1, result.skipped)
        assertEquals(0, store.insertAttempts)
        assertEquals(0, store.updateCount)
        assertSame(existing, store.books.single())
        assertEquals("saved-cover", store.books.single().customCoverUrl)
    }

    @Test
    fun temporaryBookIsActivatedInPlaceAndKeepsUserState() {
        val existing = Book(
            bookUrl = "old-url",
            name = "Temporary Book",
            author = "Temporary Author",
            type = BookType.text or BookType.notShelf,
            order = 0,
            group = 8,
            durChapterIndex = 12,
            durChapterPos = 34,
            customCoverUrl = "custom-cover",
            customIntro = "custom-intro",
        )
        val store = FakeStore(existing, minOrder = -5)

        val result = SearchBookShelfHelp.addLoadedBooksToShelf(
            listOf(searchBook("new-url", existing.name, existing.author)),
            store,
        )

        assertEquals(1, result.added)
        assertSame(existing, result.addedBooks.single())
        assertFalse(existing.isNotShelf)
        assertEquals(-6, existing.order)
        assertEquals(8, existing.group)
        assertEquals(12, existing.durChapterIndex)
        assertEquals(34, existing.durChapterPos)
        assertEquals("custom-cover", existing.customCoverUrl)
        assertEquals("custom-intro", existing.customIntro)
        assertEquals(1, store.updateCount)
        assertEquals(0, store.insertAttempts)
    }

    @Test
    fun matchingUrlActivatesTemporaryBookWithoutReplacingMetadata() {
        val existing = Book(
            bookUrl = "same-url",
            name = "Old Name",
            author = "Old Author",
            type = BookType.text or BookType.notShelf,
            order = 3,
        )
        val store = FakeStore(existing)

        val result = SearchBookShelfHelp.addLoadedBooksToShelf(
            listOf(searchBook("same-url", "New Name", "New Author")),
            store,
        )

        assertEquals(1, result.added)
        assertSame(existing, result.addedBooks.single())
        assertEquals("Old Name", existing.name)
        assertEquals("Old Author", existing.author)
        assertEquals(3, existing.order)
        assertFalse(existing.isNotShelf)
    }

    @Test
    fun ignoredInsertDoesNotReportBookAsAdded() {
        val store = FakeStore(minOrder = 6, rejectInserts = true)

        val result = SearchBookShelfHelp.addLoadedBooksToShelf(
            listOf(searchBook("conflict-url", "Conflict", "Author")),
            store,
        )

        assertEquals(0, result.added)
        assertEquals(1, result.skipped)
        assertEquals(1, store.insertAttempts)
        assertTrue(store.books.isEmpty())
    }

    @Test
    fun existingNonZeroOrderDoesNotConsumeNextNewBookOrder() {
        val temporary = Book(
            bookUrl = "temporary-url",
            name = "Temporary",
            author = "Author",
            type = BookType.text or BookType.notShelf,
            order = 42,
        )
        val store = FakeStore(temporary, minOrder = 10)

        val result = SearchBookShelfHelp.addLoadedBooksToShelf(
            listOf(
                searchBook(temporary.bookUrl, temporary.name, temporary.author),
                searchBook("new-url", "New", "Author"),
            ),
            store,
        )

        assertEquals(2, result.added)
        assertEquals(42, result.addedBooks[0].order)
        assertEquals(9, result.addedBooks[1].order)
    }

    @Test
    fun assignedOrdersNeverUseUnassignedZeroValue() {
        val store = FakeStore(minOrder = 1)

        val result = SearchBookShelfHelp.addLoadedBooksToShelf(
            listOf(
                searchBook("url-1", "Book A", "Author A"),
                searchBook("url-2", "Book B", "Author B"),
            ),
            store,
        )

        assertEquals(listOf(-2, -1), result.addedBooks.map { it.order })
    }

    @Test
    fun shelfIdentityMatchesUrlOrNameAndAuthor() {
        val activeBook = Book(
            bookUrl = "active-url",
            name = "Active Name",
            author = "Active Author",
        )

        assertTrue(
            activeBook.isSameShelfIdentity(
                Book(bookUrl = "active-url", name = "Renamed", author = "Another Author")
            )
        )
        assertTrue(
            activeBook.isSameShelfIdentity(
                Book(bookUrl = "other-url", name = "Active Name", author = "Active Author")
            )
        )
        assertFalse(
            activeBook.isSameShelfIdentity(
                Book(bookUrl = "other-url", name = "Other Name", author = "Other Author")
            )
        )
    }

    @Test
    fun sameUrlActiveBookKeepsUnsavedMemoryState() {
        val activeBook = Book(
            bookUrl = "same-url",
            name = "Active Name",
            author = "Active Author",
            type = BookType.text or BookType.notShelf,
            order = 0,
            durChapterPos = 88,
            customIntro = "unsaved-intro",
        )
        val shelfBook = Book(
            bookUrl = "same-url",
            name = "Database Name",
            author = "Database Author",
            order = -9,
            durChapterPos = 1,
        )

        val merged = mergeActiveShelfBook(activeBook, shelfBook)

        assertSame(activeBook, merged)
        assertFalse(activeBook.isNotShelf)
        assertEquals(-9, activeBook.order)
        assertEquals(88, activeBook.durChapterPos)
        assertEquals("unsaved-intro", activeBook.customIntro)
    }

    @Test
    fun sameNameDifferentUrlUsesCanonicalShelfBook() {
        val activeBook = Book(
            bookUrl = "active-url",
            name = "Same Name",
            author = "Same Author",
            durChapterPos = 88,
        )
        val shelfBook = Book(
            bookUrl = "shelf-url",
            name = "Same Name",
            author = "Same Author",
            durChapterPos = 12,
        )

        assertSame(shelfBook, mergeActiveShelfBook(activeBook, shelfBook))
    }

    private fun searchBook(bookUrl: String, name: String, author: String) = SearchBook(
        bookUrl = bookUrl,
        origin = "source-url",
        originName = "Source",
        name = name,
        author = author,
    )

    private class FakeStore(
        vararg initialBooks: Book,
        override val minOrder: Int = 0,
        private val rejectInserts: Boolean = false,
    ) : SearchBookShelfHelp.Store {
        val books = initialBooks.toMutableList()
        var insertAttempts = 0
        var updateCount = 0

        override fun getBook(name: String, author: String): Book? {
            return books.firstOrNull { it.name == name && it.author == author }
        }

        override fun getBook(bookUrl: String): Book? {
            return books.firstOrNull { it.bookUrl == bookUrl }
        }

        override fun update(book: Book) {
            updateCount++
        }

        override fun insertIgnore(book: Book): Boolean {
            insertAttempts++
            if (rejectInserts) return false
            if (books.any { it.bookUrl == book.bookUrl }) return false
            if (books.any { it.name == book.name && it.author == book.author }) return false
            books.add(book)
            return true
        }
    }
}
