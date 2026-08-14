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
            presence = offShelf(),
        )!!
        assertEquals("W", opened.book.bookUrl)
        assertFalse(opened.identityOnShelf)
        assertFalse(opened.urlOnShelf)
    }

    @Test
    fun searchUrlMarksIdentityOnlyWhenPersistKeyIsBlocked() {
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
            presence = identityOnly(),
        )!!
        assertEquals("W", opened.book.bookUrl)
        assertTrue(opened.identityOnShelf)
        assertFalse(opened.urlOnShelf)
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
            presence = offShelf(),
        )!!
        assertEquals("A", opened.book.bookUrl)
        assertTrue(opened.identityOnShelf)
        assertTrue(opened.urlOnShelf)
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
                presence = offShelf(),
            ),
        )
    }

    @Test
    fun searchToDetailChainUsesUrlFirstAndSharedPresence() {
        val info = read("src/main/java/io/legado/app/ui/book/info/BookInfoViewModel.kt")
        val search = read("src/main/java/io/legado/app/ui/book/search/SearchActivity.kt")
        val explore = read("src/main/java/io/legado/app/ui/book/explore/ExploreShowActivity.kt")
        val initData = info.substringAfter("fun initData(").substringBefore("fun upBook(")
        val upBookIntent = info.substringAfter("fun upBook(intent: Intent)").substringBefore("private fun upBook(")
        val addToBookshelf = info.substringAfter("fun addToBookshelf(").substringBefore("fun getBook(")
        val loadChapter = info.substringAfter("fun loadChapter(").substringBefore("fun loadGroup(")
        val loadBookInfo = info.substringAfter("fun loadBookInfo(").substringBefore("fun loadChapter(")
        val saveBook = info.substringAfter("fun saveBook(").substringBefore("fun saveChapterList(")
        assertTrue(initData.contains("BookInfoOpenResolver.resolve("))
        assertTrue(initData.contains("SearchBookShelfHelp.presence("))
        assertTrue(initData.contains("applyShelfPresence("))
        assertTrue(loadBookInfo.contains("refreshShelfFlags(it)"))
        assertFalse(loadBookInfo.contains("dbBook.updateTo"))
        assertTrue(saveBook.contains("SearchBookShelfHelp.persistIncomingBook(book)"))
        assertFalse(saveBook.contains("getBook(book.name, book.author)"))
        assertTrue(upBookIntent.contains("getStringExtra(\"bookUrl\")"))
        assertTrue(upBookIntent.contains("if (bookUrl.isNotBlank())"))
        assertTrue(addToBookshelf.contains("SearchBookShelfHelp.resolveOnShelf("))
        assertTrue(addToBookshelf.contains("SearchBookShelfHelp.presence("))
        assertFalse(addToBookshelf.contains("bookData.postValue"))
        assertTrue(loadChapter.contains("if (urlOnShelf)"))
        assertFalse(loadChapter.contains("if (inBookshelf)"))
        assertTrue(search.contains("putExtra(\"bookUrl\", bookUrl)"))
        assertTrue(explore.contains("putExtra(\"bookUrl\", book.bookUrl)"))
        val activity = read("src/main/java/io/legado/app/ui/book/info/BookInfoActivity.kt")
        val readBook = activity.substringAfter("private fun readBook(").substringBefore("private fun startReadActivity(")
        assertTrue(readBook.contains("else if (viewModel.urlOnShelf)"))
        assertTrue(activity.contains("putExtra(\"inBookshelf\", viewModel.urlOnShelf)"))
        assertFalse(activity.contains("putExtra(\"inBookshelf\", viewModel.inBookshelf)"))
        assertTrue(activity.contains("R.string.local_book_identity_conflict"))
        assertTrue(activity.contains("if (!viewModel.urlOnShelf)"))
    }

    private fun offShelf() = SearchBookShelfHelp.ShelfPresence(
        existing = null,
        identityOnShelf = false,
        urlOnShelf = false,
    )

    private fun identityOnly() = SearchBookShelfHelp.ShelfPresence(
        existing = Book(bookUrl = "local://x", name = "T", author = ""),
        identityOnShelf = true,
        urlOnShelf = false,
    )

    private fun read(pathInApp: String): String {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .first { it.isFile }
            .readText()
    }
}
