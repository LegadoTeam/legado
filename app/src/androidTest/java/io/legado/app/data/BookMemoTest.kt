package io.legado.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookMemo
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookMemoTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "book-memo-${UUID.randomUUID()}"
    private var database: AppDatabase? = null
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    private fun open(): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addMigrations(*DatabaseMigrations.migrations).allowMainThreadQueries().build()
        .also { database = it }

    @After fun cleanUp() {
        database?.close()
        context.deleteDatabase(name)
    }

    @Test fun migrationPreservesExistingReadingHistory() {
        helper.createDatabase(name, 107).use {
            it.execSQL("insert into readRecord (deviceId, bookName, author, readTime) values ('d', 'book', 'author', 123)")
        }
        helper.runMigrationsAndValidate(name, 108, true, *DatabaseMigrations.migrations).close()
        val db = open()
        assertEquals(123L, db.readRecordDao.getRecord("d", "book", "author")!!.readTime)
        assertTrue(db.bookMemoDao.all().isEmpty())
    }

    @Test fun reimportAndReopenPreserveMarkdownAndTimestamp() {
        var db = open()
        val book = Book(bookUrl = "https://memo/one", name = "one")
        db.bookDao.insert(book)
        db.bookMemoDao.save(book.bookUrl, "# 标题\n\n**备忘**", 100)
        db.bookDao.insert(book.copy(intro = "Imported again"))
        db.bookDao.replace(book, book.copy(intro = "Same URL replacement"))
        database!!.close()
        db = open()
        assertEquals(BookMemo(book.bookUrl, "# 标题\n\n**备忘**", 100), db.bookMemoDao.get(book.bookUrl))
    }

    @Test fun sourceReplacementMovesMemoAndPreservesNewerDestination() {
        val db = open()
        val old = Book(bookUrl = "https://memo/old", name = "old")
        val next = Book(bookUrl = "https://memo/new", name = "new")
        db.bookDao.insert(old)
        db.bookMemoDao.save(old.bookUrl, "original", 100)
        db.bookDao.replace(old, next)
        assertNull(db.bookMemoDao.get(old.bookUrl))
        assertEquals("original", db.bookMemoDao.get(next.bookUrl)!!.content)
        db.bookDao.insert(old)
        db.bookMemoDao.save(old.bookUrl, "newer destination", 200)
        db.bookDao.replace(next, old)
        assertNull(db.bookMemoDao.get(next.bookUrl))
        assertEquals("newer destination", db.bookMemoDao.get(old.bookUrl)!!.content)
    }

    @Test fun clearAndRestoreDoNotResurrectOlderTextOrCreateOrphans() {
        val db = open()
        val book = Book(bookUrl = "https://memo/clear", name = "clear")
        db.bookDao.insert(book)
        db.bookMemoDao.save(book.bookUrl, "old text", 100)
        val backup = db.bookMemoDao.all()
        db.bookMemoDao.save(book.bookUrl, "", 101)
        db.bookMemoDao.restore(backup + BookMemo("https://memo/missing", "orphan", 999))
        assertEquals("", db.bookMemoDao.get(book.bookUrl)!!.content)
        assertNull(db.bookMemoDao.get("https://memo/missing"))
        db.bookMemoDao.restore(listOf(BookMemo(book.bookUrl, "newer", 102)))
        assertEquals("newer", db.bookMemoDao.get(book.bookUrl)!!.content)
    }

    @Test fun deletionCleansOnlyOwnedMemosAndFailedTransactionKeepsOldState() {
        val db = open()
        val saved = Book(bookUrl = "https://memo/saved", name = "saved")
        val temporary = Book(bookUrl = "https://memo/temporary", name = "temporary", type = BookType.text or BookType.notShelf)
        db.bookDao.insert(saved, temporary)
        db.bookMemoDao.save(saved.bookUrl, "saved", 100)
        db.bookMemoDao.save(temporary.bookUrl, "temporary", 100)
        assertThrows(IllegalStateException::class.java) {
            db.runInTransaction {
                db.bookDao.replace(saved, saved.copy(bookUrl = "https://memo/replacement"))
                error("Abort source change")
            }
        }
        assertEquals("saved", db.bookMemoDao.get(saved.bookUrl)!!.content)
        assertNull(db.bookMemoDao.get("https://memo/replacement"))
        db.bookDao.deleteNotShelfBook()
        assertNull(db.bookMemoDao.get(temporary.bookUrl))
        assertNotNull(db.bookMemoDao.get(saved.bookUrl))
        db.bookDao.delete(saved)
        assertTrue(db.bookMemoDao.all().isEmpty())
        assertThrows(IllegalStateException::class.java) { db.bookMemoDao.save(saved.bookUrl, "late callback") }
    }
}
