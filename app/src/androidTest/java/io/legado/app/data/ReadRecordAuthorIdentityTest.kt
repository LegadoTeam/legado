package io.legado.app.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.AppConst
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.saveWithCover
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

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
            helper.runMigrationsAndValidate(name, 107, true, *DatabaseMigrations.migrations).close()
            // Also preserve those records when opening the current generated Room database.
            val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(*DatabaseMigrations.migrations)
                .allowMainThreadQueries()
                .build()
            try {
                val dao = database.readRecordDao
                assertEquals(111, database.openHelper.writableDatabase.version)
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
    fun migrate109To111PreservesLargeHistoryAndIndexesBothActualDaoQueries() {
        val name = "read-record-index-migration-${UUID.randomUUID()}"
        val records = (0 until 6375).map { index ->
            ReadRecord(deviceId = "phone", bookName = "History $index", author = "Author $index",
                readTime = index + 1L, lastRead = index + 10L, lastChapterTitle = "Chapter $index",
                lastChapterIndex = index, lastChapterPos = index % 50, coverUrl = "/saved/$index.cover")
        }.toMutableList()
        records += records.first().copy(deviceId = "tablet", readTime = 200, lastRead = 9000,
            lastChapterTitle = "Tablet chapter", lastChapterIndex = 12, lastChapterPos = 17, coverUrl = "tablet-cover")
        records += records.last().copy(deviceId = "desktop", readTime = 300,
            lastChapterTitle = "Tie winner", coverUrl = "desktop-cover")
        records += records.first().copy(author = "", readTime = 400)
        records += records.first().copy(author = combinedAuthor, readTime = 500)
        val expected = records.groupBy { it.bookName to it.author }.values.map { devices ->
            val snapshot = devices.sortedWith(compareByDescending<ReadRecord> { it.lastRead }
                .thenBy { it.deviceId }).first()
            ReadRecordShow(snapshot.bookName, devices.sumOf { it.readTime }, snapshot.lastRead,
                snapshot.author, snapshot.lastChapterTitle, snapshot.lastChapterIndex,
                snapshot.lastChapterPos, snapshot.coverUrl)
        }.toSet()
        val actualQuery = AtomicReference<Pair<String, List<Any?>>>()
        try {
            helper.createDatabase(name, 109).use { legacy ->
                legacy.beginTransaction()
                try {
                    val statement = legacy.compileStatement("INSERT INTO readRecord VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    try {
                        records.forEach { record ->
                            statement.bindString(1, record.deviceId)
                            statement.bindString(2, record.bookName)
                            statement.bindString(3, record.author)
                            statement.bindLong(4, record.readTime)
                            statement.bindLong(5, record.lastRead)
                            statement.bindString(6, record.lastChapterTitle!!)
                            statement.bindLong(7, record.lastChapterIndex.toLong())
                            statement.bindLong(8, record.lastChapterPos.toLong())
                            statement.bindString(9, record.coverUrl!!)
                            statement.executeInsert()
                        }
                    } finally { statement.close() }
                    legacy.setTransactionSuccessful()
                } finally { legacy.endTransaction() }
            }
            // Opening the generated Room database runs and validates the real migration.
            val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(*DatabaseMigrations.migrations)
                .setQueryCallback(object : RoomDatabase.QueryCallback {
                    override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                        if (sqlQuery.trimStart().startsWith("select history.bookName")) {
                            actualQuery.set(sqlQuery to bindArgs.toList())
                        }
                    }
                }, Executor { it.run() }).build()
            try {
                val sqlite = database.openHelper.writableDatabase
                assertEquals(111, sqlite.version)
                assertEquals(records.toSet(), database.readRecordDao.all.toSet())
                for (search in listOf(false, true)) {
                    val start = android.os.SystemClock.elapsedRealtime()
                    val result = if (search) database.readRecordDao.search("History") else database.readRecordDao.allShow
                    val elapsed = android.os.SystemClock.elapsedRealtime() - start
                    assertEquals(expected, result.toSet())
                    val (query, args) = checkNotNull(actualQuery.getAndSet(null))
                    val plan = sqlite.query("EXPLAIN QUERY PLAN $query", args.toTypedArray()).use { cursor ->
                        buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }
                    }
                    assertTrue("Latest snapshot must use the covering index: $plan",
                        plan.any { it.contains("SEARCH readRecord USING COVERING INDEX index_readRecord_snapshot") })
                    assertFalse("The correlated query must not scan all records: $plan",
                        plan.any { it.contains("SCAN readRecord") })
                    assertTrue("Indexed history query took ${elapsed}ms", elapsed < 3000)
                    println("History query: search=$search rows=${result.size} elapsedMs=$elapsed plan=$plan")
                }
            } finally { database.close() }
        } finally { context.deleteDatabase(name) }
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
    fun firstKnownAuthorReadingMergesUnknownDevicesOnceAndKeepsOtherIdentities() {
        val dao = appDb.readRecordDao
        val saved = dao.all
        val name = "Unknown author ${UUID.randomUUID()}"
        val known = ReadRecord(deviceId = AppConst.androidId, bookName = name, author = "A",
            readTime = 2000, lastRead = 20, lastChapterTitle = "Known", lastChapterIndex = 2)
        val unknown = known.copy(author = "", readTime = 7000, lastRead = 70,
            lastChapterTitle = "Newer unknown", lastChapterIndex = 7, lastChapterPos = 17)
        val remote = unknown.copy(deviceId = "remote", readTime = 11000)
        val remoteKnown = known.copy(deviceId = "remote", readTime = 5000, lastRead = 90,
            lastChapterTitle = "Newest remote", lastChapterIndex = 9)
        val other = known.copy(author = "B", readTime = 3000)
        val otherTitle = unknown.copy(bookName = "Different $name", readTime = 13000)
        val combined = unknown.copy(author = combinedAuthor, readTime = 17000)
        val currentBook = Book(bookUrl = "unknown:$name", name = name, author = "A")
        try {
            dao.clear()
            dao.insert(known, unknown, remote, remoteKnown, other, otherTitle, combined)
            val original = dao.all.toSet()
            val total = dao.allTime
            // Cover-only saves and mismatched book snapshots must not claim unknown history.
            known.saveWithCover(currentBook)
            known.saveWithCover(currentBook.copy(author = "B"), 0)
            assertEquals(original, dao.all.toSet())
            known.copy(lastRead = 100, lastChapterTitle = "Reading now", lastChapterIndex = 10)
                .saveWithCover(currentBook, 1000)
            val expectedLocal = known.copy(readTime = 3000, lastRead = 100,
                lastChapterTitle = "Reading now", lastChapterIndex = 10)
            val expectedRemote = remoteKnown
            assertEquals(setOf(expectedLocal, expectedRemote, unknown.copy(resolvedAuthor = "A"),
                remote.copy(resolvedAuthor = "A"), other, otherTitle, combined), dao.all.toSet())
            assertEquals(total + 1000, dao.allTime)
            assertEquals(26000L, dao.allShow.single { it.bookName == name && it.author == "A" }.readTime)
            assertFalse(dao.allShow.any { it.bookName == name && it.author.isEmpty() })
            assertEquals("A", dao.getRecord(known.deviceId, name, "")!!.resolvedAuthor)
            assertEquals("A", dao.getRecord("remote", name, "")!!.resolvedAuthor)
            // Reading a second known author must never steal the first reader's merged time.
            other.copy(lastRead = 110).saveWithCover(currentBook.copy(author = "B"), 500)
            assertEquals(expectedLocal, dao.getRecord(known.deviceId, name, "A"))
            assertEquals(expectedRemote, dao.getRecord("remote", name, "A"))
            assertEquals(3500L, dao.getRecord(known.deviceId, name, "B")!!.readTime)
            assertEquals(total + 1500, dao.allTime)
        } finally {
            dao.clear()
            dao.insert(*saved.toTypedArray())
        }
    }

    @Test
    fun authorAttributionSurvivesRepeatedOldBackupRestoreWithoutRecountingTime() {
        val dao = appDb.readRecordDao
        val saved = dao.all
        val name = "Author restore ${UUID.randomUUID()}"
        val directory = File(context.cacheDir, "author-attribution-${UUID.randomUUID()}").apply { mkdirs() }
        val local = ReadRecord(deviceId = AppConst.androidId, bookName = name, readTime = 7000,
            lastRead = 100, lastChapterTitle = "Original blank", lastChapterIndex = 1)
        val remote = local.copy(deviceId = "remote", readTime = 11000, lastRead = 200,
            lastChapterTitle = "Remote blank", lastChapterIndex = 2)
        val book = Book(bookUrl = "author-restore:$name", name = name, author = "A")
        val archive = File(directory, "readRecord.json")
        fun restore() = runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.absolutePath, lanTransfer = true) }
        try {
            val oldJson = GSON.toJsonTree(listOf(local, remote)).asJsonArray
            oldJson.forEach { it.asJsonObject.remove("resolvedAuthor") }
            archive.writeText(GSON.toJson(oldJson))
            dao.clear()
            restore()
            assertEquals(18000L, dao.allTime)
            assertEquals("", dao.allShow.single().author)
            local.copy(author = "A", lastRead = 300).saveWithCover(book, 1000)
            var total = 19000L
            assertEquals(total, dao.allTime)
            assertEquals(total, dao.allShow.single().readTime)
            repeat(2) {
                val before = dao.all.toSet()
                restore()
                assertEquals("An unchanged backup must not resurrect consumed time", before, dao.all.toSet())
                assertEquals(total, dao.allTime)
                local.copy(author = "A", lastRead = 400 + it.toLong()).saveWithCover(book, 500)
                total += 500
                assertEquals(total, dao.allShow.single().readTime)
            }
            local.copy(author = "B", lastRead = 500).saveWithCover(book.copy(author = "B"), 250)
            assertEquals(20000L, dao.allShow.single { it.author == "A" }.readTime)
            assertEquals(250L, dao.allShow.single { it.author == "B" }.readTime)
            assertEquals("A", dao.getRecord(local.deviceId, name, "")!!.resolvedAuthor)
            assertEquals("A", dao.getRecord("remote", name, "")!!.resolvedAuthor)
            // A queued blank-author snapshot and a cover-only save must preserve the claim.
            local.copy(lastRead = 600, lastChapterTitle = "Latest blank").saveWithCover(book.copy(author = ""), 100)
            val blank = dao.getRecord(local.deviceId, name, "")!!
            blank.copy(resolvedAuthor = null).saveWithCover(book.copy(author = ""))
            assertEquals(20350L, dao.allTime)
            val shown = dao.allShow.single { it.author == "A" }
            assertEquals(20100L, shown.readTime)
            assertEquals("Latest blank", shown.lastChapterTitle)
            assertEquals(shown, dao.search("A").single { it.author == "A" })
            assertEquals(setOf("A", "B"), runBlocking { dao.flowBooks().first().map { it.author }.toSet() })
            val assigned = dao.all.toSet()
            archive.writeText(GSON.toJson(prepareReadRecordBackup(assigned.toList(),
                context.getExternalFilesDir(null)!!, directory, false)))
            dao.clear()
            restore()
            assertEquals("The selected author must travel with a new backup", assigned, dao.all.toSet())
            assertEquals(20350L, dao.allTime)
            dao.deleteByBook(name, "A")
            assertEquals(250L, dao.allTime)
            assertEquals("B", dao.all.single().author)
        } finally {
            dao.clear()
            dao.insert(*saved.toTypedArray())
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
                    assertEquals(unknown.readTime + resolved.readTime, dao.allShow.single { it.author == "Resolved author" }.readTime)
                    assertEquals("Resolved chapter", resolved.lastChapterTitle)
                    assertEquals("Resolved author", dao.getRecord(AppConst.androidId, name, "")!!.resolvedAuthor)
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

    @Test
    fun queuedReaderIntervalsSurviveSwitchingFromAuthorAToBAndBack() {
        val dao = appDb.readRecordDao
        val enabled = AppConfig.enableReadRecord
        val name = "Queued identity ${UUID.randomUUID()}"
        val first = ReadRecord(deviceId = AppConst.androidId, bookName = name, author = "A", readTime = 10_000)
        val other = first.copy(author = "B", readTime = 50_000)
        val remote = first.copy(deviceId = "remote", readTime = 900_000)
        val unknown = first.copy(author = "", readTime = 7000)
        val saved = dao.all
        try {
            AppConfig.enableReadRecord = true
            for (model in listOf(ReadBook, ReadManga, AudioPlay)) {
                globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
                val type = model.javaClass
                val bookField = type.getDeclaredField("book").apply { isAccessible = true }
                val oldBook = bookField.get(model)
                val isAudio = model === AudioPlay
                val timer = if (isAudio) {
                    type.getDeclaredField("readTimeTracker").apply { isAccessible = true }.get(model)
                } else model
                val timerFields = (if (isAudio) listOf("record", "activeRecord", "startedAt")
                    else listOf("readRecord", "readStartTime"))
                    .map { timer.javaClass.getDeclaredField(it).apply { isAccessible = true } }
                val oldTimer = timerFields.map { it.get(timer) }
                try {
                    dao.clear()
                    dao.insert(first, other, remote, unknown)
                    val expected = mutableMapOf("A" to first.readTime + unknown.readTime, "B" to other.readTime)
                    withReadRecordWritesPaused {
                        // Exercise the real loaders and interval writers while their shared
                        // executor cannot save anything; no wait is allowed between identities.
                        for ((author, duration) in listOf("" to 500L, "A" to 1000L, "B" to 3000L, "A" to 2000L)) {
                            val current = Book(bookUrl = "queued:$author", name = name, author = author,
                                durChapterTitle = "Chapter $duration")
                            val elapsed: Long
                            if (isAudio) {
                                AudioPlay.replaceBook(current)
                                AudioPlay.markReadTimeStart()
                                val active = timerFields[1].get(timer) as ReadRecord
                                val before = active.readTime
                                timerFields[2].set(timer, android.os.SystemClock.elapsedRealtime() - duration)
                                AudioPlay.upReadTime()
                                elapsed = active.readTime - before
                            } else {
                                bookField.set(model, current)
                                type.getDeclaredMethod("resetReadRecord", Book::class.java)
                                    .apply { isAccessible = true }.invoke(model, current)
                                val start = System.currentTimeMillis() - duration
                                timerFields[1].setLong(timer, start)
                                type.getDeclaredMethod("upReadTime").invoke(model)
                                elapsed = timerFields[1].getLong(timer) - start
                            }
                            val creditedAuthor = author.ifEmpty { "A" }
                            expected[creditedAuthor] = expected.getValue(creditedAuthor) + elapsed
                        }
                        assertEquals(first, dao.getRecord(first.deviceId, name, "A"))
                        assertEquals(other, dao.getRecord(first.deviceId, name, "B"))
                    }
                    for ((author, total) in expected) {
                        assertEquals("${type.simpleName}: every interval survives", total,
                            dao.allShow.single { it.bookName == name && it.author == author }.readTime -
                                if (author == "A") remote.readTime else 0L)
                    }
                    assertEquals("Chapter 2000", dao.getRecord(first.deviceId, name, "A")!!.lastChapterTitle)
                    assertEquals("Chapter 3000", dao.getRecord(first.deviceId, name, "B")!!.lastChapterTitle)
                    assertEquals(remote, dao.getRecord("remote", name, "A"))
                    assertEquals("A", dao.getRecord(first.deviceId, name, "")!!.resolvedAuthor)
                } finally {
                    globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
                    bookField.set(model, oldBook)
                    timerFields.zip(oldTimer).forEach { (field, value) -> field.set(timer, value) }
                }
            }
        } finally {
            dao.clear()
            dao.insert(*saved.toTypedArray())
            AppConfig.enableReadRecord = enabled
        }
    }

    @Test
    fun queuedProgressKeepsEachBooksPositionAndSourceWhenTheReaderSwitches() {
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        val originalBook = ReadBook.book
        val originalSource = ReadBook.bookSource
        val originalIndex = ReadBook.durChapterIndex
        val originalPosition = ReadBook.durChapterPos
        val jumpField = ReadBook.javaClass.getDeclaredField("pendingHighlightJump").apply { isAccessible = true }
        val originalJump = jumpField.get(ReadBook)
        val id = UUID.randomUUID().toString()
        val sources = listOf("A", "B").map { author ->
            BookSource(bookSourceUrl = "https://example.invalid/queued-progress/$id/$author",
                bookSourceName = "Queued progress $author", enabled = false, eventListener = true,
                ruleContent = ContentRule(callBackJs = """
                    if (event === 'saveRead') {
                        source.putVariable([source.getKey(), book.bookUrl, chapter.bookUrl,
                            chapter.index, book.durChapterIndex, book.durChapterPos,
                            book.durChapterTitle, result].join('|'));
                    }
                """.trimIndent()))
        }
        val books = sources.mapIndexed { index, source ->
            Book(bookUrl = "${source.bookSourceUrl}/book", name = "Queued progress $id $index",
                origin = source.bookSourceUrl, author = "Author $index",
                durChapterIndex = 0, durChapterPos = 10 + index,
                durChapterTitle = "Initial $index", durChapterTime = 1, lastCheckCount = 7).apply {
                setUseReplaceRule(false)
            }
        }
        val evidence = StringBuilder()
        val firstWritten = AtomicReference<List<Book>>()
        var queuedAt = 0L
        var releaseAt = 0L
        try {
            appDb.bookSourceDao.insert(*sources.toTypedArray())
            appDb.bookDao.insert(*books.toTypedArray())
            books.forEach { book ->
                appDb.bookChapterDao.insert(*(0..2).map { index ->
                    BookChapter(bookUrl = book.bookUrl, url = "${book.bookUrl}/$index",
                        index = index, title = "${book.author} chapter $index")
                }.toTypedArray())
            }
            withReadRecordWritesPaused {
                queuedAt = System.currentTimeMillis()
                books.forEachIndexed { index, book ->
                    // Switch the real reader state without starting unrelated chapter/network loads.
                    ReadBook.book = book
                    ReadBook.bookSource = sources[index]
                    ReadBook.durChapterIndex = index + 1
                    ReadBook.durChapterPos = 123 * (index + 1)
                    ReadBook.saveRead(pageChanged = index == 1)
                    if (index == 0) {
                        globalExecutor.execute {
                            firstWritten.set(books.map { appDb.bookDao.getBook(it.bookUrl)!! })
                        }
                    }
                }
                // Neither queued write may borrow a subsequent visible position from book B.
                ReadBook.durChapterIndex = 0
                ReadBook.durChapterPos = 999
                books.forEachIndexed { index, book ->
                    val saved = appDb.bookDao.getBook(book.bookUrl)!!
                    assertEquals(0, saved.durChapterIndex)
                    assertEquals(10 + index, saved.durChapterPos)
                    assertEquals(1L, saved.durChapterTime)
                }
                assertTrue(sources.all { it.getVariable().isEmpty() })
                evidence.appendLine("Queue blocked: A=0/10 B=0/11; queued A=1/123 B=2/246; visible B=0/999")
                releaseAt = System.currentTimeMillis()
            }
            val intermediate = checkNotNull(firstWritten.get())
            evidence.appendLine("After first writer: " + intermediate.joinToString { "${it.author}=${it.durChapterIndex}/${it.durChapterPos}" })
            assertEquals(1, intermediate[0].durChapterIndex)
            assertEquals(123, intermediate[0].durChapterPos)
            assertEquals(0, intermediate[1].durChapterIndex)
            assertEquals(11, intermediate[1].durChapterPos)
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            while (sources.any { it.getVariable().isEmpty() } &&
                android.os.SystemClock.elapsedRealtime() < deadline) {
                android.os.SystemClock.sleep(20)
            }
            books.forEachIndexed { index, book ->
                val saved = appDb.bookDao.getBook(book.bookUrl)!!
                val chapterIndex = index + 1
                val position = 123 * chapterIndex
                val callback = sources[index].getVariable()
                evidence.appendLine("Saved ${book.author}: ${saved.durChapterIndex}/${saved.durChapterPos} time=${saved.durChapterTime}; callback=$callback")
                assertEquals(chapterIndex, saved.durChapterIndex)
                assertEquals(position, saved.durChapterPos)
                assertEquals("${book.author} chapter $chapterIndex", saved.durChapterTitle)
                assertEquals(0, saved.lastCheckCount)
                assertTrue("Save time must belong to the enqueue operation",
                    saved.durChapterTime in queuedAt..releaseAt)
                assertEquals(listOf(sources[index].bookSourceUrl, book.bookUrl, book.bookUrl,
                    chapterIndex, chapterIndex, position, saved.durChapterTitle,
                    saved.durChapterTime).joinToString("|"), callback)
            }
            assertSame(books[1], ReadBook.book)
            assertEquals(0, ReadBook.durChapterIndex)
            assertEquals(999, ReadBook.durChapterPos)
        } finally {
            File(context.getExternalFilesDir("ui-regression"), "queued-read-progress.txt")
                .writeText(evidence.toString())
            globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
            ReadBook.book = originalBook
            ReadBook.bookSource = originalSource
            ReadBook.durChapterIndex = originalIndex
            ReadBook.durChapterPos = originalPosition
            jumpField.set(ReadBook, originalJump)
            appDb.bookDao.delete(*books.toTypedArray())
            sources.forEach { source ->
                source.putVariable(null)
                appDb.bookSourceDao.delete(source.bookSourceUrl)
            }
        }
    }

    @Test
    fun anOlderQueuedIntervalAddsTimeWithoutReplacingTheNewerSnapshot() {
        val dao = appDb.readRecordDao
        val name = "Out-of-order interval ${UUID.randomUUID()}"
        val current = ReadRecord(deviceId = "phone", bookName = name, author = "A", readTime = 10_000,
            lastRead = 200, lastChapterTitle = "New chapter", lastChapterIndex = 9, lastChapterPos = 80)
        try {
            dao.insert(current)
            current.copy(readTime = 1000, lastRead = 100, lastChapterTitle = "Old chapter",
                lastChapterIndex = 1, lastChapterPos = 0).saveWithCover(null, 2000)
            assertEquals(current.copy(readTime = 12_000), dao.getRecord("phone", name, "A"))
        } finally {
            dao.deleteByBook(name, "A")
        }
    }

    private fun withReadRecordWritesPaused(block: () -> Unit) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gate = globalExecutor.submit {
            started.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Reader blocked on its queued write" }
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            block()
        } finally {
            release.countDown()
            gate.get(10, TimeUnit.SECONDS)
            globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        }
    }

    private fun withDatabase(block: (AppDatabase) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try { block(database) } finally { database.close() }
    }
}
