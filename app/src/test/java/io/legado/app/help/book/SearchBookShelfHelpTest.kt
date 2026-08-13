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
        assertTrue(store.updateCount >= 1)
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
    fun shelfIdentityMatchesUrlOrEffectiveAuthor() {
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
                Book(bookUrl = "other-url", name = "Active Name", author = "佚名")
            )
        )
        assertFalse(
            activeBook.isSameShelfIdentity(
                Book(bookUrl = "other-url", name = "Other Name", author = "Other Author")
            )
        )
    }

    @Test
    fun addingYimingDoesNotDuplicateSoleRealAuthor() {
        val existing = Book(
            bookUrl = "real-url",
            name = "高考后，开始成为提取系男神",
            author = "七月观天",
            order = 3,
        )
        val store = FakeStore(existing)
        val result = SearchBookShelfHelp.addLoadedBooksToShelf(
            listOf(searchBook("weak-url", existing.name, "佚名")),
            store,
        )
        assertEquals(0, result.added)
        assertEquals(1, store.books.size)
        assertEquals("七月观天", store.books.single().author)
        assertEquals("real-url", store.books.single().bookUrl)
    }

    @Test
    fun addingRealAuthorUpgradesSoleYimingRow() {
        val existing = Book(
            bookUrl = "weak-url",
            name = "高考后，开始成为提取系男神",
            author = "佚名",
            order = 3,
            durChapterIndex = 5,
            durChapterPos = 10,
        )
        val store = FakeStore(existing)
        val result = SearchBookShelfHelp.addLoadedBooksToShelf(
            listOf(searchBook("real-url", existing.name, "七月观天")),
            store,
        )
        assertEquals(0, result.added)
        assertEquals(1, store.books.size)
        assertEquals("七月观天", store.books.single().author)
        assertEquals(5, store.books.single().durChapterIndex)
    }

    @Test
    fun yimingDoesNotAttachWhenTwoRealAuthorsExist() {
        val a = Book(bookUrl = "a", name = "同名书", author = "作者甲", order = 1)
        val b = Book(bookUrl = "b", name = "同名书", author = "作者乙", order = 2)
        val store = FakeStore(a, b)
        SearchBookShelfHelp.addLoadedBooksToShelf(
            listOf(searchBook("weak", "同名书", "佚名")),
            store,
        )
        assertEquals(3, store.books.size)
        assertTrue(store.books.any { it.author == "" || it.author == "佚名" || it.bookUrl == "weak" })
    }

    @Test
    fun coalesceMergesExistingYimingAndRealDuplicates() {
        val weak = Book(
            bookUrl = "weak",
            name = "同名书",
            author = "佚名",
            durChapterTime = 100,
            durChapterIndex = 1,
        )
        val real = Book(
            bookUrl = "real",
            name = "同名书",
            author = "七月观天",
            durChapterTime = 50,
            durChapterIndex = 2,
        )
        val store = FakeStore(weak, real)
        SearchBookShelfHelp.coalesceSameName(store, "同名书")
        assertEquals(1, store.books.size)
        assertEquals("real", store.books.single().bookUrl)
        assertEquals("七月观天", store.books.single().author)
        // weak had newer durChapterTime → progress copied
        assertEquals(1, store.books.single().durChapterIndex)
    }

    @Test
    fun coalesceLeavesWeaksAloneWhenTwoRealsExist() {
        val a = Book(bookUrl = "a", name = "同名书", author = "作者甲")
        val b = Book(bookUrl = "b", name = "同名书", author = "作者乙")
        val weak = Book(bookUrl = "w", name = "同名书", author = "佚名")
        val store = FakeStore(a, b, weak)
        SearchBookShelfHelp.coalesceSameName(store, "同名书")
        assertEquals(3, store.books.size)
        assertEquals(0, store.deleteCount)
    }

    @Test
    fun coalesceDoesNotRetireLocalBook() {
        val local = Book(
            bookUrl = "local://x",
            name = "同名书",
            author = "佚名",
            type = BookType.local or BookType.text,
        )
        val web = Book(
            bookUrl = "https://web/x",
            name = "同名书",
            author = "七月观天",
        )
        val store = FakeStore(local, web)
        SearchBookShelfHelp.coalesceSameName(store, "同名书")
        assertEquals(2, store.books.size)
        assertTrue(store.books.any { it.bookUrl == "local://x" })
        assertTrue(store.books.any { it.bookUrl == "https://web/x" })
    }

    @Test
    fun localWeakAuthorIsNotFilledWithRealAuthor() {
        val local = Book(
            bookUrl = "local://x",
            name = "同名书",
            author = "佚名",
            type = BookType.local or BookType.text,
        )
        val store = FakeStore(local)
        SearchBookShelfHelp.addLoadedBooksToShelf(
            listOf(searchBook("https://web/x", "同名书", "七月观天")),
            store,
        )
        val localRow = store.books.first { it.bookUrl == "local://x" }
        assertEquals("佚名", localRow.author)
        assertTrue(store.books.any { it.bookUrl == "local://x" })
    }

    @Test
    fun collidingAuthorFillDoesNotCreateSecondRealKey() {
        val weak = Book(bookUrl = "weak", name = "同名书", author = "佚名")
        val real = Book(bookUrl = "real", name = "同名书", author = "七月观天")
        val store = FakeStore(weak, real)
        SearchBookShelfHelp.addLoadedBooksToShelf(
            listOf(searchBook("incoming", "同名书", "七月观天")),
            store,
        )
        val reals = store.books.filter {
            BookAuthorIdentity.effectiveAuthor(it.author) == "七月观天"
        }
        assertEquals(1, reals.size)
        assertEquals("real", reals.single().bookUrl)
        assertEquals(1, store.books.size)
    }

    @Test
    fun shelfBadgeMatchesWeakAndSoleReal() {
        val real = Book(bookUrl = "r", name = "高考后", author = "七月观天")
        val keys = SearchBookShelfHelp.shelfBadgeKeys(listOf(real))
        assertTrue(
            SearchBookShelfHelp.isInShelfBadgeIndex(
                searchBook("other", "高考后", "佚名"),
                keys,
            )
        )
        assertTrue(
            SearchBookShelfHelp.isInShelfBadgeIndex(
                searchBook("other2", "高考后", "七月观天"),
                keys,
            )
        )
        assertFalse(
            SearchBookShelfHelp.isInShelfBadgeIndex(
                searchBook("x", "别的书", "佚名"),
                keys,
            )
        )
    }

    @Test
    fun shelfBadgeDoesNotCollapseTwoRealAuthors() {
        val a = Book(bookUrl = "a", name = "同名书", author = "作者甲")
        val b = Book(bookUrl = "b", name = "同名书", author = "作者乙")
        val keys = SearchBookShelfHelp.shelfBadgeKeys(listOf(a, b))
        assertFalse(
            SearchBookShelfHelp.isInShelfBadgeIndex(
                searchBook("w", "同名书", "佚名"),
                keys,
            )
        )
        assertTrue(
            SearchBookShelfHelp.isInShelfBadgeIndex(
                searchBook("a2", "同名书", "作者甲"),
                keys,
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
        var deleteCount = 0

        override fun getBook(name: String, author: String): Book? {
            return books.firstOrNull { it.name == name && it.author == author }
        }

        override fun getBook(bookUrl: String): Book? {
            return books.firstOrNull { it.bookUrl == bookUrl }
        }

        override fun getBooksByName(name: String): List<Book> {
            val n = name.trim()
            return books.filter { it.name.trim() == n }
        }

        override fun update(book: Book) {
            updateCount++
        }

        override fun delete(book: Book) {
            deleteCount++
            books.removeAll { it.bookUrl == book.bookUrl }
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
