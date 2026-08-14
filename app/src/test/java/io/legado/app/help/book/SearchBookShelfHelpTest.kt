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
    fun addingYimingPrefersSoleRealOverExistingYiming() {
        val weak = Book(bookUrl = "weak", name = "同名书", author = "佚名")
        val real = Book(bookUrl = "real", name = "同名书", author = "七月观天")
        val store = FakeStore(weak, real)
        val result = SearchBookShelfHelp.addLoadedBooksToShelf(
            listOf(searchBook("incoming-weak", "同名书", "佚名")),
            store,
        )
        assertEquals(0, result.added)
        assertEquals(2, store.books.size)
        assertEquals(
            "real",
            SearchBookShelfHelp.resolveExisting(
                store,
                searchBook("incoming-weak", "同名书", "佚名"),
            )?.bookUrl,
        )
        assertTrue(store.books.any { it.bookUrl == "weak" })
        assertTrue(store.books.any { it.bookUrl == "real" })
        assertEquals(0, store.deleteCount)
    }

    @Test
    fun persistNewBookDoesNotReplaceLocalYiming() {
        val local = Book(
            bookUrl = "local://x",
            name = "同名书",
            author = "佚名",
            type = BookType.local or BookType.text,
        )
        val store = FakeStore(local)
        val incoming = Book(bookUrl = "https://web/x", name = "同名书", author = "佚名")
        val persisted = SearchBookShelfHelp.persistNewBook(store, incoming)
        assertEquals("https://web/x", persisted?.bookUrl)
        assertEquals("", incoming.author)
        assertEquals(2, store.books.size)
        assertEquals("佚名", store.books.first { it.bookUrl == "local://x" }.author)
        assertEquals(0, store.deleteCount)
    }

    @Test
    fun localYimingDoesNotMarkWebYimingInShelfAndStillInserts() {
        val local = Book(
            bookUrl = "local://x",
            name = "同名书",
            author = "佚名",
            type = BookType.local or BookType.text,
        )
        val incoming = searchBook("weak", "同名书", "佚名")
        val keys = SearchBookShelfHelp.shelfBadgeKeys(listOf(local))
        assertFalse(SearchBookShelfHelp.isInShelfBadgeIndex(incoming, keys))
        assertTrue(
            SearchBookShelfHelp.isInShelfBadgeIndex(
                searchBook("local://x", "同名书", "佚名"),
                keys,
            )
        )
        val store = FakeStore(local)
        val result = SearchBookShelfHelp.addLoadedBooksToShelf(listOf(incoming), store)
        assertEquals(1, result.added)
        assertEquals(2, store.books.size)
        assertTrue(store.books.any { it.bookUrl == "local://x" })
        assertTrue(store.books.any { it.bookUrl == "weak" })
        assertEquals(0, store.deleteCount)
    }

    @Test
    fun localPaddedNameRealDoesNotMarkTrimmedWebRealAndStillInserts() {
        val local = Book(
            bookUrl = "local://x",
            name = "T ",
            author = "A",
            type = BookType.local or BookType.text,
        )
        val incoming = searchBook("W", "T", "A")
        val keys = SearchBookShelfHelp.shelfBadgeKeys(listOf(local))
        assertFalse(SearchBookShelfHelp.isInShelfBadgeIndex(incoming, keys))
        val store = FakeStore(local)
        val result = SearchBookShelfHelp.addLoadedBooksToShelf(listOf(incoming), store)
        assertEquals(1, result.added)
        assertEquals(2, store.books.size)
        assertTrue(store.books.any { it.bookUrl == "local://x" })
        assertTrue(store.books.any { it.bookUrl == "W" })
        assertEquals(0, store.deleteCount)
    }

    @Test
    fun localEmptyAuthorMarksWebYimingAndSkipInsert() {
        val local = Book(
            bookUrl = "local://x",
            name = "T",
            author = "",
            type = BookType.local or BookType.text,
        )
        val incoming = searchBook("W", "T", "佚名")
        val keys = SearchBookShelfHelp.shelfBadgeKeys(listOf(local))
        assertTrue(SearchBookShelfHelp.isInShelfBadgeIndex(incoming, keys))
        val store = FakeStore(local)
        val result = SearchBookShelfHelp.addLoadedBooksToShelf(listOf(incoming), store)
        assertEquals(0, result.added)
        assertEquals(1, store.books.size)
        assertEquals("local://x", store.books.single().bookUrl)
        assertEquals(0, store.deleteCount)
    }

    @Test
    fun localExactRealMarksWebRealAndBlocksInsert() {
        val local = Book(
            bookUrl = "local://x",
            name = "同名书",
            author = "七月观天",
            type = BookType.local or BookType.text,
        )
        val incoming = searchBook("W", "同名书", "七月观天")
        val keys = SearchBookShelfHelp.shelfBadgeKeys(listOf(local))
        assertTrue(SearchBookShelfHelp.isInShelfBadgeIndex(incoming, keys))
        val store = FakeStore(local)
        val result = SearchBookShelfHelp.addLoadedBooksToShelf(listOf(incoming), store)
        assertEquals(0, result.added)
        assertEquals(1, store.books.size)
        assertEquals("local://x", store.books.single().bookUrl)
        assertEquals(0, store.deleteCount)
    }

    @Test
    fun batchAddLoadsShelfSnapshotOnce() {
        val store = FakeStore(
            Book(bookUrl = "a", name = "甲", author = "A"),
            Book(bookUrl = "b", name = "乙", author = "B"),
        )
        SearchBookShelfHelp.addLoadedBooksToShelf(
            listOf(
                searchBook("1", "书一", "作者一"),
                searchBook("2", "书二", "作者二"),
                searchBook("3", "书三", "作者三"),
            ),
            store,
        )
        assertEquals(1, store.allBooksLoads)
        assertEquals(3, store.insertAttempts)
    }

    @Test
    fun batchInsertIsVisibleToLaterSameNameWeakSkip() {
        val store = FakeStore()
        val result = SearchBookShelfHelp.addLoadedBooksToShelf(
            listOf(
                searchBook("real", "同名书", "作者甲"),
                searchBook("weak", "同名书", "佚名"),
            ),
            store,
        )
        assertEquals(1, result.added)
        assertEquals(1, store.books.size)
        assertEquals("real", store.books.single().bookUrl)
        assertEquals(1, store.allBooksLoads)
    }

    @Test
    fun notShelfRealDoesNotAbsorbIncomingWeak() {
        val temp = Book(
            bookUrl = "A",
            name = "T",
            author = "作者甲",
            type = BookType.text or BookType.notShelf,
        )
        val incoming = searchBook("B", "T", "佚名")
        val keys = SearchBookShelfHelp.shelfBadgeKeys(listOf(temp))
        assertFalse(SearchBookShelfHelp.isInShelfBadgeIndex(incoming, keys))
        assertFalse(SearchBookShelfHelp.isIncomingOnVisibleShelf(incoming, FakeStore(temp)))
        val store = FakeStore(temp)
        val result = SearchBookShelfHelp.addLoadedBooksToShelf(listOf(incoming), store)
        assertEquals(1, result.added)
        assertEquals(2, store.books.size)
        assertTrue(store.books.first { it.bookUrl == "A" }.isNotShelf)
        assertEquals("B", result.addedBooks.single().bookUrl)
        assertEquals(0, store.deleteCount)
    }

    @Test
    fun soleWebRealMarksWeakIncomingOnShelfForListAndDetail() {
        val real = Book(bookUrl = "A", name = "T", author = "作者甲")
        val incoming = searchBook("B", "T", "佚名")
        val store = FakeStore(real)
        val keys = SearchBookShelfHelp.shelfBadgeKeys(listOf(real))
        assertTrue(SearchBookShelfHelp.isInShelfBadgeIndex(incoming, keys))
        assertTrue(SearchBookShelfHelp.isIncomingOnVisibleShelf(incoming, store))
        val result = SearchBookShelfHelp.addLoadedBooksToShelf(listOf(incoming), store)
        assertEquals(0, result.added)
        assertEquals(1, store.books.size)
        assertEquals("A", store.books.single().bookUrl)
        val presence = SearchBookShelfHelp.presence(incoming, store)
        assertTrue(presence.identityOnShelf)
        assertFalse(presence.urlOnShelf)
        assertEquals("A", presence.existing?.bookUrl)
    }

    @Test
    fun localSameAuthorIsIdentityOnlyNotUrlOnShelf() {
        val local = Book(
            bookUrl = "local://A",
            name = "T",
            author = "作者甲",
            type = BookType.local or BookType.text,
        )
        val incoming = searchBook("https://B", "T", "作者甲")
        val presence = SearchBookShelfHelp.presence(incoming, FakeStore(local))
        assertTrue(presence.identityOnShelf)
        assertFalse(presence.urlOnShelf)
        assertEquals("local://A", presence.existing?.bookUrl)
    }

    @Test
    fun sameUrlIsBothIdentityAndUrlOnShelf() {
        val web = Book(bookUrl = "https://A", name = "T", author = "作者甲")
        val presence = SearchBookShelfHelp.presence(
            searchBook("https://A", "T", "作者甲"),
            FakeStore(web),
        )
        assertTrue(presence.identityOnShelf)
        assertTrue(presence.urlOnShelf)
        assertEquals("https://A", presence.existing?.bookUrl)
    }

    @Test
    fun notShelfEmptyAuthorDoesNotMarkWebYimingOnShelf() {
        val temp = Book(
            bookUrl = "A",
            name = "T",
            author = "",
            type = BookType.text or BookType.notShelf,
        )
        val incoming = searchBook("B", "T", "佚名")
        assertFalse(SearchBookShelfHelp.isIncomingOnVisibleShelf(incoming, FakeStore(temp)))
        assertFalse(
            SearchBookShelfHelp.isInShelfBadgeIndex(
                incoming,
                SearchBookShelfHelp.shelfBadgeKeys(listOf(temp)),
            )
        )
    }

    @Test
    fun persistNewBookSkipsWhenLocalOwnsExactRealKey() {
        val local = Book(
            bookUrl = "local://x",
            name = "同名书",
            author = "七月观天",
            type = BookType.local or BookType.text,
        )
        val store = FakeStore(local)
        val incoming = Book(bookUrl = "https://web/x", name = "同名书", author = "七月观天")
        val persisted = SearchBookShelfHelp.persistNewBook(store, incoming)
        assertEquals(null, persisted)
        assertEquals(1, store.books.size)
        assertEquals("local://x", store.books.single().bookUrl)
        assertEquals(0, store.deleteCount)
    }

    @Test
    fun paddedShelfNameAndTrimmedSearchShareBadgeAndSkipInsert() {
        val existing = Book(
            bookUrl = "real",
            name = "同名书 ",
            author = "作者甲",
        )
        val incoming = searchBook("weak", "同名书", "佚名")
        val keys = SearchBookShelfHelp.shelfBadgeKeys(listOf(existing))
        assertTrue(SearchBookShelfHelp.isInShelfBadgeIndex(incoming, keys))
        val store = FakeStore(existing)
        val result = SearchBookShelfHelp.addLoadedBooksToShelf(listOf(incoming), store)
        assertEquals(0, result.added)
        assertEquals(1, store.books.size)
        assertEquals("real", store.books.single().bookUrl)
        assertEquals("同名书 ", store.books.single().name)
        assertEquals(0, store.deleteCount)
    }

    @Test
    fun localRealDoesNotGiveWeakBadgeAndIncomingYimingStillInserts() {
        val local = Book(
            bookUrl = "local://x",
            name = "同名书",
            author = "作者甲",
            type = BookType.local or BookType.text,
        )
        val incoming = searchBook("weak", "同名书", "佚名")
        val keys = SearchBookShelfHelp.shelfBadgeKeys(listOf(local))
        assertFalse(SearchBookShelfHelp.isInShelfBadgeIndex(incoming, keys))
        assertTrue(
            SearchBookShelfHelp.isInShelfBadgeIndex(
                searchBook("local://x", "同名书", "作者甲"),
                keys,
            )
        )
        val store = FakeStore(local)
        SearchBookShelfHelp.addLoadedBooksToShelf(listOf(incoming), store)
        assertEquals(2, store.books.size)
        assertTrue(store.books.any { it.bookUrl == "local://x" })
        assertTrue(store.books.any { it.bookUrl == "weak" })
        assertEquals(0, store.deleteCount)
    }

    @Test
    fun addingRealAuthorDoesNotRewriteExistingYiming() {
        val existing = Book(
            bookUrl = "weak-url",
            name = "高考后，开始成为提取系男神",
            author = "佚名",
            order = 3,
            durChapterIndex = 5,
            durChapterPos = 10,
        )
        val store = FakeStore(existing)
        SearchBookShelfHelp.addLoadedBooksToShelf(
            listOf(searchBook("real-url", existing.name, "七月观天")),
            store,
        )
        assertEquals(2, store.books.size)
        assertEquals("佚名", store.books.first { it.bookUrl == "weak-url" }.author)
        assertEquals("七月观天", store.books.first { it.bookUrl == "real-url" }.author)
        assertEquals(0, store.deleteCount)
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
    fun sequentialAddsDoNotGuessWhenSecondRealAuthorArrives() {
        val store = FakeStore()
        SearchBookShelfHelp.addLoadedBooksToShelf(
            listOf(searchBook("weak", "同名书", "佚名")),
            store,
        )
        SearchBookShelfHelp.addLoadedBooksToShelf(
            listOf(searchBook("a", "同名书", "作者甲")),
            store,
        )
        SearchBookShelfHelp.addLoadedBooksToShelf(
            listOf(searchBook("b", "同名书", "作者乙")),
            store,
        )
        assertEquals(3, store.books.size)
        assertEquals(0, store.deleteCount)
        assertTrue(store.books.any { it.bookUrl == "weak" })
        assertTrue(store.books.any { it.author == "作者甲" })
        assertTrue(store.books.any { it.author == "作者乙" })
    }

    @Test
    fun localWeakAuthorIsNotFilledAndWebRealIsStillInserted() {
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
        assertTrue(store.books.any { it.bookUrl == "https://web/x" })
        assertEquals(0, store.deleteCount)
    }

    @Test
    fun localRealDoesNotAbsorbIncomingYiming() {
        val local = Book(
            bookUrl = "local://x",
            name = "同名书",
            author = "七月观天",
            type = BookType.local or BookType.text,
        )
        val store = FakeStore(local)
        SearchBookShelfHelp.addLoadedBooksToShelf(
            listOf(searchBook("weak", "同名书", "佚名")),
            store,
        )
        assertEquals(2, store.books.size)
        assertTrue(store.books.any { it.bookUrl == "local://x" })
        assertTrue(store.books.any { it.bookUrl == "weak" })
        assertEquals(0, store.deleteCount)
    }

    @Test
    fun addingRealWhenWeakAndRealExistDoesNotDeleteWeak() {
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
        assertEquals(2, store.books.size)
        assertTrue(store.books.any { it.bookUrl == "weak" })
        assertEquals(0, store.deleteCount)
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
        var allBooksLoads = 0

        override fun getBook(name: String, author: String): Book? {
            return books.firstOrNull { it.name == name && it.author == author }
        }

        override fun getBook(bookUrl: String): Book? {
            return books.firstOrNull { it.bookUrl == bookUrl }
        }

        override fun getBooksByName(name: String): List<Book> {
            return books.filter { BookAuthorIdentity.equalName(it.name, name) }
        }

        override fun allBooks(): List<Book> {
            allBooksLoads++
            return books.toList()
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
