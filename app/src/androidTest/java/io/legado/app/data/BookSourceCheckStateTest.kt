package io.legado.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourceCheckState
import io.legado.app.utils.GSON
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookSourceCheckStateTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "source-check-${UUID.randomUUID()}"
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

    @Test fun completedAndInterruptedEntriesSurviveDatabaseReopen() {
        val dao = open().bookSourceDao
        val sources = (0..2).map { BookSource(bookSourceUrl = "https://check/$it", bookSourceName = "Source $it") }
        dao.insert(*sources.toTypedArray())
        val queue = dao.beginCheck(dao.allPart)
        assertTrue(dao.completeCheck(queue[0], true, "", 12))
        assertTrue(dao.completeCheck(queue[1], false, "搜索失效", 45))
        database!!.close()
        val reopened = open().bookSourceDao
        assertEquals(listOf("PASSED", "FAILED", "NEEDS_CHECK"), queue.map { reopened.getCheckState(it.bookSourceUrl)!!.status })
        assertEquals("搜索失效", reopened.getCheckState(queue[1].bookSourceUrl)!!.detail)
        assertEquals(0L, reopened.getCheckState(queue[2].bookSourceUrl)!!.checkedAt)
    }

    @Test fun editedDeletedImportedAndRestartedChecksRejectOldResults() {
        val dao = open().bookSourceDao
        val source = BookSource(bookSourceUrl = "https://check/source", bookSourceName = "Source", lastUpdateTime = 1)
        dao.insert(source)
        var queued = dao.beginCheck(dao.allPart).single()
        dao.update(source.copy(searchUrl = "https://changed", lastUpdateTime = 1))
        assertFalse(dao.completeCheck(queued, true, "stale edit", 1))
        assertEquals("NEEDS_CHECK", dao.getCheckState(source.bookSourceUrl)!!.status)
        queued = dao.beginCheck(dao.allPart).single()
        dao.delete(source)
        assertNull(dao.getCheckState(source.bookSourceUrl))
        dao.insert(source)
        assertFalse(dao.completeCheck(queued, true, "stale deletion", 1))
        queued = dao.beginCheck(dao.allPart).single()
        dao.insert(source)
        assertFalse(dao.completeCheck(queued, true, "stale import", 1))
        queued = dao.beginCheck(dao.allPart).single()
        val next = dao.beginCheck(dao.allPart).single()
        assertFalse(dao.completeCheck(queued, true, "stale session", 1))
        assertTrue(dao.completeCheck(next, true, "", 1))
        assertFalse(dao.completeCheck(next, false, "duplicate callback", 1))
    }

    @Test fun statusDoesNotPolluteSourceAndRankingDoesNotInvalidateRules() {
        val dao = open().bookSourceDao
        val source = BookSource(bookSourceUrl = "https://check/source", bookSourceName = "Source",
            bookSourceGroup = "小说,自定义", bookSourceComment = "User comment")
        dao.insert(source)
        val queued = dao.beginCheck(dao.allPart).single()
        assertTrue(dao.completeCheck(queued, false, "网络超时", 50))
        val current = dao.getBookSource(source.bookSourceUrl)!!
        assertEquals(source.bookSourceGroup, current.bookSourceGroup)
        assertEquals(source.bookSourceComment, current.bookSourceComment)
        assertFalse(GSON.toJson(current).contains("FAILED"))
        dao.update(current.copy(weight = 50, customOrder = 3))
        assertEquals("FAILED", dao.getCheckState(source.bookSourceUrl)!!.status)
        assertEquals("FAILED", dao.getBookSourcePart(source.bookSourceUrl)!!.checkStatus)
        dao.insert(current) // Import/restore deliberately requires a fresh device check.
        assertEquals("NEEDS_CHECK", dao.getCheckState(source.bookSourceUrl)!!.status)
    }

    @Test fun upgradeFrom104PreservesSourcesAndHistoryAndInitializesUnknownStatus() {
        helper.createDatabase(name, 104).apply {
            execSQL("""INSERT INTO book_sources(bookSourceUrl, bookSourceName, bookSourceType,
                lastUpdateTime, respondTime, weight) VALUES('https://old', 'Old', 0, 0, 0, 0)""")
            execSQL("INSERT INTO readRecord(deviceId, bookName, readTime) VALUES('local', 'Old book', 123)")
            close()
        }
        val db = open()
        assertEquals("Old", db.bookSourceDao.getBookSource("https://old")!!.bookSourceName)
        assertEquals("NEEDS_CHECK", db.bookSourceDao.getBookSourcePart("https://old")!!.checkStatus)
        assertFalse(db.bookSourceDao.getCheckState("https://old")!!.sourceRevision.isEmpty())
        db.openHelper.readableDatabase.query("SELECT readTime, lastChapterIndex FROM readRecord").use {
            assertTrue(it.moveToFirst())
            assertEquals(123L, it.getLong(0))
            assertEquals(-1, it.getInt(1))
        }
    }

    @Test fun upgradeFrom105PreservesExistingChapterSnapshot() {
        helper.createDatabase(name, 104).apply {
            DatabaseMigrations.migrations.single { it.startVersion == 104 && it.endVersion == 105 }.migrate(this)
            execSQL("""INSERT INTO readRecord(deviceId, bookName, readTime, lastChapterTitle,
                lastChapterIndex, lastChapterPos, coverUrl) VALUES('local', 'Snapshot', 99, 'Chapter 7', 6, 25, 'cover')""")
            version = 105
            close()
        }
        open().openHelper.readableDatabase.query("SELECT lastChapterTitle, lastChapterPos, coverUrl FROM readRecord").use {
            assertTrue(it.moveToFirst())
            assertEquals("Chapter 7", it.getString(0))
            assertEquals(25, it.getInt(1))
            assertEquals("cover", it.getString(2))
        }
    }
}
