package io.legado.app.ui.main.bookshelf.style2

import io.legado.app.constant.BookType
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookshelfBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookshelfGroupItemTest {

    @Test
    fun selectsAtMostFourBooksUsingEachGroupSort() {
        val books = (1..5).reversed().map { index ->
            book("book-$index", group = 3, order = index)
        }
        val groups = listOf(
            BookGroup(groupId = 1, bookSort = 3),
            BookGroup(groupId = 2, cover = "custom-cover", bookSort = 3),
        )

        val items = buildBookshelfGroupItems(groups, books)

        assertEquals(listOf("book-1", "book-2", "book-3", "book-4"), urls(items[0]))
        assertTrue(items[1].previewBooks.isEmpty())
    }

    @Test
    fun matchesSystemAndUserGroups() {
        val books = listOf(
            book("local-grouped", type = BookType.text or BookType.local, group = 1),
            book("local-none", type = BookType.text or BookType.local),
            book("net-none"),
            book("user-net", group = 1),
            book("hidden-group", group = 2, hasUserGroup = true),
            book("audio", type = BookType.audio),
            book("video", type = BookType.video),
            book("error", type = BookType.text or BookType.updateError),
            book("legacy", type = 0, origin = BookType.localTag),
        )
        val groups = listOf(
            BookGroup(BookGroup.IdAll, bookSort = 3),
            BookGroup(BookGroup.IdLocal, bookSort = 3),
            BookGroup(BookGroup.IdAudio, bookSort = 3),
            BookGroup(BookGroup.IdNetNone, bookSort = 3),
            BookGroup(BookGroup.IdLocalNone, bookSort = 3),
            BookGroup(BookGroup.IdVideo, bookSort = 3),
            BookGroup(BookGroup.IdError, bookSort = 3),
            BookGroup(1, bookSort = 3),
        )
        val items = buildBookshelfGroupItems(groups, books).associateBy { it.group.groupId }

        assertEquals(
            setOf("local-grouped", "local-none", "net-none", "user-net"),
            urls(items.getValue(BookGroup.IdAll)).toSet(),
        )
        assertEquals(
            setOf("local-grouped", "local-none"),
            urls(items.getValue(BookGroup.IdLocal)).toSet(),
        )
        assertEquals(listOf("audio"), urls(items.getValue(BookGroup.IdAudio)))
        assertEquals(setOf("net-none", "error", "legacy"), urls(items.getValue(BookGroup.IdNetNone)).toSet())
        assertEquals(listOf("local-none"), urls(items.getValue(BookGroup.IdLocalNone)))
        assertEquals(listOf("video"), urls(items.getValue(BookGroup.IdVideo)))
        assertEquals(listOf("error"), urls(items.getValue(BookGroup.IdError)))
        assertEquals(setOf("local-grouped", "user-net"), urls(items.getValue(1)).toSet())
    }

    @Test
    fun supportsEveryBookshelfSortMode() {
        val books = listOf(
            book("b", name = "B", order = 2, latest = 20, read = 40),
            book("a", name = "A", order = 3, latest = 30, read = 10),
            book("c", name = "C", order = 1, latest = 10, read = 20),
        )

        assertEquals(listOf("a", "b", "c"), sortBookshelfBooks(books, 1).map { it.bookUrl })
        assertEquals(listOf("a", "b", "c"), sortBookshelfBooks(books, 2).map { it.bookUrl })
        assertEquals(listOf("c", "b", "a"), sortBookshelfBooks(books, 3).map { it.bookUrl })
        assertEquals(listOf("b", "a", "c"), sortBookshelfBooks(books, 4).map { it.bookUrl })
        assertEquals(listOf("b", "c", "a"), sortBookshelfBooks(books, 0).map { it.bookUrl })
    }

    @Test
    fun customCoverOnlyUsesCredentialsOnTheSameOrigin() {
        val sameOrigin = book(
            "same",
            customCover = "https://books.example.com/custom.jpg",
        )
        val otherOrigin = book(
            "other",
            customCover = "https://images.example.com/custom.jpg",
        )

        assertEquals(sameOrigin.customCoverUrl, sameOrigin.displayCover)
        assertEquals(sameOrigin.origin, sameOrigin.coverSourceOrigin)
        assertNull(otherOrigin.coverSourceOrigin)

        val persisted = sameOrigin.copy(persistedCoverUrl = "/covers/local.cover")
        assertEquals(persisted.persistedCoverUrl, persisted.displayCover)
        assertNull(persisted.coverSourceOrigin)
    }

    private fun urls(item: BookshelfGroupItem) = item.previewBooks.map { it.bookUrl }

    private fun book(
        id: String,
        name: String = id,
        type: Int = BookType.text,
        group: Long = 0,
        order: Int = 0,
        latest: Long = 0,
        read: Long = 0,
        customCover: String? = null,
        persistedCover: String? = null,
        hasUserGroup: Boolean = group > 0,
        origin: String = "https://books.example.com",
    ) = BookshelfBook(
        bookUrl = id,
        origin = origin,
        name = name,
        author = "author",
        coverUrl = "https://books.example.com/$id.jpg",
        customCoverUrl = customCover,
        type = type,
        group = group,
        hasUserGroup = hasUserGroup,
        latestChapterTime = latest,
        durChapterTime = read,
        persistedCoverUrl = persistedCover,
        order = order,
    )
}
