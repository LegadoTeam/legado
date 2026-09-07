package io.legado.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.AppConst
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.Book
import io.legado.app.help.config.AppConfig
import io.legado.app.help.globalExecutor
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import io.legado.app.model.AudioPlay
import io.legado.app.help.book.ReadRecordCoverCache
import io.legado.app.help.storage.Restore
import io.legado.app.help.storage.prepareReadRecordBackup
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ReadRecordAuthorIdentityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val combinedAuthor = "\u001Eauthors:[\"Author A\",\"Author B\"]"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate106To107PreservesUnknownAndCombinedAuthorsWithoutSplittingTheirTime() {
        val name = "read-record-author-migration-${UUID.randomUUID()}"
        val legacy = listOf(
            ReadRecord(deviceId = "phone", bookName = "Same", author = "", readTime = 410,
                lastRead = 51, lastChapterTitle = "Unknown author's chapter", lastChapterIndex = 4,
                lastChapterPos = 17, coverUrl = "/old/unknown.png"),
            ReadRecord(deviceId = "old-device", bookName = "Same", author = combinedAuthor, readTime = 730,
                lastRead = 82, lastChapterTitle = "Combined history", lastChapterIndex = 8,
                lastChapterPos = 21, coverUrl = "/old/combined.png"),
            ReadRecord(deviceId = "tablet", bookName = "Same", author = "Author A", readTime = 120,
                lastRead = 31, lastChapterTitle = null, lastChapterIndex = -1, lastChapterPos = 0, coverUrl = null),
            ReadRecord(deviceId = "phone", bookName = "Different", author = "Author B", readTime = 90,
                lastRead = 15, lastChapterTitle = "Other book", lastChapterIndex = 2,
                lastChapterPos = 6, coverUrl = "https://covers.example/other.png"),
        )
        try {
            helper.createDatabase(name, 106).apply {
                legacy.forEach { record ->
                    execSQL(
                        """insert into readRecord (deviceId, bookName, author, readTime, lastRead,
                            lastChapterTitle, lastChapterIndex, lastChapterPos, coverUrl)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                        arrayOf<Any?>(record.deviceId, record.bookName, record.author, record.readTime,
                            record.lastRead, record.lastChapterTitle, record.lastChapterIndex,
                            record.lastChapterPos, record.coverUrl),
                    )
                }
                close()
            }
            // Opening the generated Room database validates the migrated table against version 107.
            val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(*DatabaseMigrations.migrations)
                .allowMainThreadQueries()
                .build()
            try {
                val dao = database.readRecordDao
                assertEquals(107, database.openHelper.writableDatabase.version)
                assertEquals(legacy.toSet(), dao.all.toSet())
                assertEquals(1350L, dao.allTime)
                assertNull(dao.getRecord("phone", "Same", "Author A"))
                assertNull(dao.getRecord("old-device", "Same", "Author A"))
                assertNull(dao.getRecord("old-device", "Same", "Author B"))
                assertEquals("Author A、Author B", dao.allShow.single { it.author == combinedAuthor }.displayAuthor)
                dao.insert(ReadRecord(deviceId = "phone", bookName = "Same", author = "Author A", readTime = 70))
                dao.insert(ReadRecord(deviceId = "phone", bookName = "Same", author = "Author B", readTime = 30))
                assertEquals(6, dao.all.size)
                assertEquals(legacy[0], dao.getRecord("phone", "Same", ""))
                assertEquals(legacy[1], dao.getRecord("old-device", "Same", combinedAuthor))
                assertEquals(70L, dao.getReadTime("phone", "Same", "Author A"))
                assertEquals(30L, dao.getReadTime("phone", "Same", "Author B"))
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun daoAggregatesDevicesButSeparatesAuthorsForSearchAndDeletion() = withDatabase { database ->
        val dao = database.readRecordDao
        val first = ReadRecord(deviceId = "phone", bookName = "Same", author = "Author A", readTime = 100,
            lastRead = 10, lastChapterTitle = "Old A", lastChapterIndex = 1, coverUrl = "old-a")
        val latest = first.copy(deviceId = "tablet", readTime = 200, lastRead = 20,
            lastChapterTitle = "Latest A", lastChapterIndex = 9, lastChapterPos = 31, coverUrl = "latest-a")
        val other = first.copy(author = "Author B", readTime = 900, lastRead = 90,
            lastChapterTitle = "B chapter", lastChapterIndex = 70, coverUrl = "b-cover")
        val unknown = first.copy(author = "", readTime = 40)
        val combined = first.copy(author = combinedAuthor, readTime = 60)
        dao.insert(first, latest, other, unknown, combined)
        assertEquals(5, dao.all.size)
        assertEquals(1300L, dao.allTime)
        assertEquals(100L, dao.getReadTime("phone", "Same", "Author A"))
        assertEquals(900L, dao.getReadTime("phone", "Same", "Author B"))
        assertEquals(setOf("Author A", "Author B", "", combinedAuthor), runBlocking {
            dao.flowBooks().first().map { it.author }.toSet()
        })
        val aggregate = dao.allShow.single { it.author == "Author A" }
        assertEquals(300L, aggregate.readTime)
        assertEquals(20L, aggregate.lastRead)
        assertEquals("Latest A", aggregate.lastChapterTitle)
        assertEquals(9, aggregate.lastChapterIndex)
        assertEquals(31, aggregate.lastChapterPos)
        assertEquals("latest-a", aggregate.coverUrl)
        assertEquals(aggregate, dao.search("Author A").single { it.author == "Author A" })
        assertEquals(setOf("Author A", combinedAuthor), dao.search("Author A").map { it.author }.toSet())
        assertEquals(4, dao.search("Same").size)
        assertEquals(900L, dao.allShow.single { it.author == "Author B" }.readTime)
        dao.deleteByBook("Same", "Author A")
        assertEquals(setOf(other, unknown, combined), dao.all.toSet())
        assertNull(dao.getRecord("tablet", "Same", "Author A"))
        assertEquals(setOf("Author B", "", combinedAuthor), runBlocking {
            dao.flowBooks().first().map { it.author }.toSet()
        })
    }

    @Test
    fun coverCompareAndSetCannotTouchAnotherAuthorOrRecreateDeletedHistory() = withDatabase { database ->
        val dao = database.readRecordDao
        val first = ReadRecord(deviceId = "phone", bookName = "Same", author = "Author A", readTime = 100,
            lastRead = 10, lastChapterTitle = "A chapter", coverUrl = "shared-original")
        val other = first.copy(author = "Author B", readTime = 900, lastChapterTitle = "B chapter")
        dao.insert(first, other)
        assertEquals(1, dao.updateCoverIfUnchanged("phone", "Same", "Author A", "shared-original", "a-owned"))
        assertEquals(first.copy(coverUrl = "a-owned"), dao.getRecord("phone", "Same", "Author A"))
        assertEquals(other, dao.getRecord("phone", "Same", "Author B"))
        assertEquals(0, dao.updateCoverIfUnchanged("phone", "Same", "Author A", "shared-original", "stale"))
        assertEquals(0, dao.updateCoverIfUnchanged("phone", "Same", "", "shared-original", "unknown"))
        dao.deleteByBook("Same", "Author A")
        assertEquals(0, dao.updateCoverIfUnchanged("phone", "Same", "Author A", "a-owned", "late"))
        assertNull(dao.getRecord("phone", "Same", "Author A"))
        assertEquals(listOf(other), dao.all)
    }

    @Test
    fun backupRestoreMergesExactAuthorAndDeviceWithoutAbsorbingUnknownHistory() {
        val dao = appDb.readRecordDao
        val saved = dao.all
        val directory = File(context.cacheDir, "read-record-author-backup-${UUID.randomUUID()}").apply { mkdirs() }
        val localId = AppConst.androidId
        val first = ReadRecord(deviceId = localId, bookName = "Same", author = "Author A", readTime = 1000,
            lastRead = 100, lastChapterTitle = "Current A", lastChapterIndex = 1, coverUrl = "a-cover")
        val other = first.copy(author = "Author B", readTime = 2000, lastRead = 300,
            lastChapterTitle = "Current B", lastChapterIndex = 30, coverUrl = "b-cover")
        val remote = first.copy(deviceId = "remote", readTime = 9000, lastRead = 9000)
        val incoming = listOf(
            first.copy(readTime = 900, lastRead = 200, lastChapterTitle = "Restored A", lastChapterIndex = 20),
            other.copy(readTime = 2500, lastRead = 200, lastChapterTitle = "Older B", lastChapterIndex = 2),
            first.copy(deviceId = "", author = "", readTime = 300, lastRead = 50,
                lastChapterTitle = "Unknown history", lastChapterIndex = 5, coverUrl = null),
            first.copy(deviceId = "legacy", author = combinedAuthor, readTime = 700, lastRead = 40,
                lastChapterTitle = "Combined history", lastChapterIndex = 4, coverUrl = null),
            remote.copy(readTime = 40, lastRead = 10000, lastChapterTitle = "Remote restored", lastChapterIndex = 40),
        )
        try {
            dao.clear()
            dao.insert(first, other, remote)
            val staged = prepareReadRecordBackup(incoming, context.getExternalFilesDir(null)!!, directory, false)
            val json = GSON.toJsonTree(staged).asJsonArray
            // A real old backup has neither author nor deviceId, not a pre-normalized test record.
            json[2].asJsonObject.remove("author")
            json[2].asJsonObject.remove("deviceId")
            File(directory, "readRecord.json").writeText(GSON.toJson(json))
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.absolutePath, lanTransfer = true) }
            assertEquals(5, dao.all.size)
            assertEquals(first.copy(readTime = 1000, lastRead = 200, lastChapterTitle = "Restored A", lastChapterIndex = 20),
                dao.getRecord(localId, "Same", "Author A"))
            assertEquals(other.copy(readTime = 2500), dao.getRecord(localId, "Same", "Author B"))
            assertEquals(incoming[2].copy(deviceId = localId), dao.getRecord(localId, "Same", ""))
            assertEquals(incoming[3], dao.getRecord("legacy", "Same", combinedAuthor))
            assertEquals(incoming[4], dao.getRecord("remote", "Same", "Author A"))
            assertNull(dao.getRecord("", "Same", ""))
            assertEquals(4540L, dao.allTime)
            assertEquals(1040L, dao.allShow.single { it.author == "Author A" }.readTime)
            val once = dao.all.toSet()
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.absolutePath, lanTransfer = true) }
            assertEquals(once, dao.all.toSet())
        } finally {
            dao.clear()
            dao.insert(*saved.toTypedArray())
            ReadRecordCoverCache.prune()
            directory.deleteRecursively()
        }
    }

    @Test
    fun actualReadersResumeAndWriteOnlyTheirDeviceAndAuthor() {
        val dao = appDb.readRecordDao
        val saved = dao.all
        val enabled = AppConfig.enableReadRecord
        val name = "Reader identity ${UUID.randomUUID()}"
        val first = ReadRecord(deviceId = AppConst.androidId, bookName = name, author = "Author A", readTime = 10_000)
        val other = first.copy(author = "Author B", readTime = 50_000)
        val remote = first.copy(deviceId = "remote", readTime = 900_000)
        fun awaitWrites() { globalExecutor.submit {}.get(10, TimeUnit.SECONDS) }
        try {
            AppConfig.enableReadRecord = true
            // Invoke each real resume loader, then its real timer/write path. Reflection only
            // avoids starting chapter/network loading and preserves the shared reader state.
            for (model in listOf(ReadBook, ReadManga)) {
                awaitWrites()
                val type = model.javaClass
                val bookField = type.getDeclaredField("book").apply { isAccessible = true }
                val recordField = type.getDeclaredField("readRecord").apply { isAccessible = true }
                val timeField = type.getDeclaredField("readStartTime").apply { isAccessible = true }
                val oldBook = bookField.get(model)
                val oldRecord = recordField.get(model)
                val oldStart = timeField.getLong(model)
                try {
                    dao.clear()
                    dao.insert(first, other, remote)
                    for (baseline in listOf(first, other)) {
                        val current = Book(bookUrl = "identity:${baseline.author}", name = name, author = baseline.author)
                        val untouched = dao.all.filter { it.author != baseline.author || it.deviceId != AppConst.androidId }.toSet()
                        bookField.set(model, current)
                        type.getDeclaredMethod("resetReadRecord", Book::class.java).apply { isAccessible = true }.invoke(model, current)
                        timeField.setLong(model, System.currentTimeMillis() - 1000)
                        type.getDeclaredMethod("upReadTime").invoke(model)
                        awaitWrites()
                        val written = dao.getRecord(AppConst.androidId, name, baseline.author)!!
                        assertTrue("${type.simpleName}: author duration", written.readTime in (baseline.readTime + 1000)..(baseline.readTime + 10_000))
                        assertEquals(untouched, dao.all.filter { it.author != baseline.author || it.deviceId != AppConst.androidId }.toSet())
                    }
                    // BookInfo fills missing identity fields in place, without calling upData.
                    val unknownBook = Book(bookUrl = "identity:pending", name = name, author = "")
                    bookField.set(model, unknownBook)
                    type.getDeclaredMethod("resetReadRecord", Book::class.java).apply { isAccessible = true }.invoke(model, unknownBook)
                    timeField.setLong(model, System.currentTimeMillis() - 1000)
                    type.getDeclaredMethod("upReadTime").invoke(model)
                    awaitWrites()
                    val unknown = dao.getRecord(AppConst.androidId, name, "")!!
                    unknownBook.author = "Resolved author"
                    unknownBook.durChapterTitle = "Resolved chapter"
                    timeField.setLong(model, System.currentTimeMillis() - 1000)
                    type.getDeclaredMethod("upReadTime").invoke(model)
                    awaitWrites()
                    val resolved = dao.getRecord(AppConst.androidId, name, "Resolved author")!!
                    assertTrue(resolved.readTime in 1000L..10_000L)
                    assertEquals("Resolved chapter", resolved.lastChapterTitle)
                    assertEquals(unknown, dao.getRecord(AppConst.androidId, name, ""))
                } finally {
                    awaitWrites()
                    bookField.set(model, oldBook)
                    recordField.set(model, oldRecord)
                    timeField.setLong(model, oldStart)
                }
            }
            val oldAudioBook = AudioPlay.book
            try {
                AudioPlay.upReadTime()
                awaitWrites()
                dao.clear()
                dao.insert(first, other, remote)
                for (baseline in listOf(first, other)) {
                    val current = Book(bookUrl = "identity:${baseline.author}", name = name, author = baseline.author)
                    val untouched = dao.all.filter { it.author != baseline.author || it.deviceId != AppConst.androidId }.toSet()
                    AudioPlay.replaceBook(current)
                    AudioPlay.markReadTimeStart()
                    android.os.SystemClock.sleep(20)
                    AudioPlay.upReadTime()
                    awaitWrites()
                    val written = dao.getRecord(AppConst.androidId, name, baseline.author)!!
                    assertTrue("Audio: author duration", written.readTime in (baseline.readTime + 1)..(baseline.readTime + 10_000))
                    assertEquals(untouched, dao.all.filter { it.author != baseline.author || it.deviceId != AppConst.androidId }.toSet())
                }
            } finally {
                AudioPlay.upReadTime()
                awaitWrites()
                if (oldAudioBook != null) AudioPlay.replaceBook(oldAudioBook) else AudioPlay.book = null
            }
        } finally {
            awaitWrites()
            dao.clear()
            dao.insert(*saved.toTypedArray())
            AppConfig.enableReadRecord = enabled
        }
    }

    private fun withDatabase(block: (AppDatabase) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try { block(database) } finally { database.close() }
    }
}
