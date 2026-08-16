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
            bookUrl = "W",
            shelfByUrl = null,
            searchByUrl = web,
            shelfByNameAuthor = local,
            searchByNameAuthor = web,
        )!!
        assertEquals("W", opened.book.bookUrl)
        assertFalse(opened.inBookshelf)
    }

    @Test
    fun officialUrlIsOnShelf() {
        val web = Book(bookUrl = "A", name = "T", author = "甲")
        val opened = BookInfoOpenResolver.resolve(
            bookUrl = "A",
            shelfByUrl = web,
            searchByUrl = web,
            shelfByNameAuthor = web,
            searchByNameAuthor = web,
        )!!
        assertEquals("A", opened.book.bookUrl)
        assertTrue(opened.inBookshelf)
    }

    @Test
    fun missingUrlFallsBackToNameAuthorShelfRow() {
        val shelf = Book(bookUrl = "A", name = "T", author = "甲")
        val opened = BookInfoOpenResolver.resolve(
            bookUrl = "",
            shelfByUrl = null,
            searchByUrl = null,
            shelfByNameAuthor = shelf,
            searchByNameAuthor = null,
        )!!
        assertEquals("A", opened.book.bookUrl)
        assertTrue(opened.inBookshelf)
    }

    @Test
    fun notShelfUrlIsNotOfficial() {
        val leftover = Book(
            bookUrl = "B",
            name = "T",
            author = "佚名",
            type = BookType.notShelf,
        )
        val opened = BookInfoOpenResolver.resolve(
            bookUrl = "B",
            shelfByUrl = leftover,
            searchByUrl = leftover,
            shelfByNameAuthor = Book(bookUrl = "A", name = "T", author = "作者甲"),
            searchByNameAuthor = leftover,
        )!!
        assertEquals("B", opened.book.bookUrl)
        assertFalse(opened.inBookshelf)
    }

    @Test
    fun presentUrlDoesNotFallBackToNameAuthor() {
        assertNull(
            BookInfoOpenResolver.resolve(
                bookUrl = "W",
                shelfByUrl = null,
                searchByUrl = null,
                shelfByNameAuthor = Book(bookUrl = "local://x", name = "T", author = "佚名"),
                searchByNameAuthor = null,
            ),
        )
    }

    @Test
    fun searchToDetailChainUsesUrlFirstAndOfficialShelfOnly() {
        val info = read("src/main/java/io/legado/app/ui/book/info/BookInfoViewModel.kt")
        val search = read("src/main/java/io/legado/app/ui/book/search/SearchActivity.kt")
        val explore = read("src/main/java/io/legado/app/ui/book/explore/ExploreShowActivity.kt")
        val initData = info.substringAfter("fun initData(").substringBefore("fun upBook(")
        val upBookIntent = info.substringAfter("fun upBook(intent: Intent)").substringBefore("private fun refreshShelfFlags(")
        val addToBookshelf = info.substringAfter("fun addToBookshelf(").substringBefore("fun getBook(")
        val loadChapter = info.substringAfter("fun loadChapter(").substringBefore("fun loadGroup(")
        val saveBook = info.substringAfter("fun saveBook(").substringBefore("fun saveChapterList(")
        assertTrue(initData.contains("BookInfoOpenResolver.resolve("))
        assertTrue(initData.contains("inBookshelf = opened.inBookshelf"))
        assertTrue(saveBook.contains("SearchBookShelfHelp.persistIncomingBook(book)"))
        assertFalse(saveBook.contains("shouldSkipWeakInsert"))
        assertTrue(upBookIntent.contains("intent.getStringExtra(\"name\")"))
        assertTrue(upBookIntent.contains("appDb.bookDao.getBook(name, author)"))
        assertFalse(upBookIntent.contains("bookData.value?.bookUrl"))
        assertFalse(upBookIntent.contains("sessionBookUrl"))
        assertFalse(upBookIntent.contains("ReadManga.book"))
        assertFalse(upBookIntent.contains("resolveReturnBook"))
        assertTrue(addToBookshelf.contains("shouldSkipWeakInsert"))
        assertTrue(addToBookshelf.contains("if (saved == true)"))
        assertTrue(addToBookshelf.contains("local_book_identity_conflict"))
        assertFalse(addToBookshelf.contains("bookData.postValue"))
        assertTrue(loadChapter.contains("if (inBookshelf)"))
        assertTrue(search.contains("putExtra(\"bookUrl\", bookUrl)"))
        assertTrue(explore.contains("putExtra(\"bookUrl\", book.bookUrl)"))
        val activity = read("src/main/java/io/legado/app/ui/book/info/BookInfoActivity.kt")
        val tocResult = activity.substringAfter("private val tocActivityResult").substringBefore("private val localBookTreeSelect")
        assertTrue(tocResult.contains("if (!viewModel.inBookshelf)"))
        assertTrue(tocResult.contains("viewModel.delBook(onlyNotShelf = true)"))
        assertTrue(activity.contains("putExtra(\"inBookshelf\", viewModel.inBookshelf)"))
        assertTrue(info.contains("deleteNotShelfByUrl"))
        assertFalse(info.contains("canDeleteTempBookUrl"))
        val dao = read("src/main/java/io/legado/app/data/dao/BookDao.kt")
        assertTrue(
            dao.contains(
                "delete from books where bookUrl = :bookUrl and type & \${BookType.notShelf} > 0",
            ),
        )
        val readFinish = read("src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
        assertTrue(readFinish.contains("BookInfoShelfFlags.promoteOrSkipTempBook"))
    }

    private fun read(pathInApp: String): String {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .first { it.isFile }
            .readText()
    }
}
