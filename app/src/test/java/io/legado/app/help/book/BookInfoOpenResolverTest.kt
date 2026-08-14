package io.legado.app.help.book

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookInfoOpenResolverTest {

    @Test
    fun searchUrlKeepsPageWhenLocalOwnsSameRawNameAuthor() {
        val local = Book(
            bookUrl = "local://x",
            name = "T",
            author = "佚名",
            type = BookType.local or BookType.text,
        )
        val web = Book(bookUrl = "W", name = "T", author = "佚名")
        val opened = BookInfoOpenResolver.resolve(
            name = "T",
            author = "佚名",
            bookUrl = "W",
            shelfByUrl = null,
            searchByUrl = web,
            shelfByNameAuthor = local,
            searchByNameAuthor = web,
            incomingOnShelf = false,
        )!!
        assertEquals("W", opened.book.bookUrl)
        assertFalse(opened.inBookshelf)
    }

    @Test
    fun searchUrlMarksOnShelfWhenPersistKeyIsBlocked() {
        val web = Book(bookUrl = "W", name = "T", author = "佚名")
        val opened = BookInfoOpenResolver.resolve(
            name = "T",
            author = "佚名",
            bookUrl = "W",
            shelfByUrl = null,
            searchByUrl = web,
            shelfByNameAuthor = Book(
                bookUrl = "local://x",
                name = "T",
                author = "",
                type = BookType.local or BookType.text,
            ),
            searchByNameAuthor = web,
            incomingOnShelf = true,
        )!!
        assertEquals("W", opened.book.bookUrl)
        assertTrue(opened.inBookshelf)
    }

    @Test
    fun missingUrlFallsBackToNameAuthorShelfRow() {
        val shelf = Book(bookUrl = "A", name = "T", author = "甲")
        val opened = BookInfoOpenResolver.resolve(
            name = "T",
            author = "甲",
            bookUrl = "",
            shelfByUrl = null,
            searchByUrl = null,
            shelfByNameAuthor = shelf,
            searchByNameAuthor = null,
            incomingOnShelf = false,
        )!!
        assertEquals("A", opened.book.bookUrl)
        assertTrue(opened.inBookshelf)
    }

    @Test
    fun presentUrlDoesNotFallBackToNameAuthor() {
        assertNull(
            BookInfoOpenResolver.resolve(
                name = "T",
                author = "佚名",
                bookUrl = "W",
                shelfByUrl = null,
                searchByUrl = null,
                shelfByNameAuthor = Book(bookUrl = "local://x", name = "T", author = "佚名"),
                searchByNameAuthor = null,
                incomingOnShelf = false,
            ),
        )
    }

    @Test
    fun searchToDetailChainUsesUrlFirstAndSharedAddIdentity() {
        val info = read("src/main/java/io/legado/app/ui/book/info/BookInfoViewModel.kt")
        val search = read("src/main/java/io/legado/app/ui/book/search/SearchActivity.kt")
        val explore = read("src/main/java/io/legado/app/ui/book/explore/ExploreShowActivity.kt")
        val initData = info.substringAfter("fun initData(").substringBefore("fun upBook(")
        val upBookIntent = info.substringAfter("fun upBook(intent: Intent)").substringBefore("private fun upBook(")
        val addToBookshelf = info.substringAfter("fun addToBookshelf(").substringBefore("fun getBook(")
        assertTrue(initData.contains("BookInfoOpenResolver.resolve("))
        assertTrue(initData.contains("SearchBookShelfHelp.isIncomingOnVisibleShelf("))
        assertTrue(upBookIntent.contains("getStringExtra(\"bookUrl\")"))
        assertTrue(upBookIntent.contains("if (bookUrl.isNotBlank())"))
        assertTrue(addToBookshelf.contains("SearchBookShelfHelp.resolveOnShelf("))
        assertTrue(addToBookshelf.contains("SearchBookShelfHelp.isIncomingOnVisibleShelf("))
        assertFalse(addToBookshelf.contains("bookData.postValue"))
        assertTrue(search.contains("putExtra(\"bookUrl\", bookUrl)"))
        assertTrue(explore.contains("putExtra(\"bookUrl\", book.bookUrl)"))
    }

    private fun read(pathInApp: String): String {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .first { it.isFile }
            .readText()
    }
}
