package io.legado.app.ui.about

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.saveReadRecordSnapshot
import io.legado.app.data.entities.saveWithCover
import io.legado.app.databinding.ActivityReadRecordBinding
import io.legado.app.databinding.ItemReadRecordDisplayBinding
import io.legado.app.help.book.ReadRecordCoverCache
import io.legado.app.help.config.AppConfig
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.Restore
import io.legado.app.help.storage.writePreferenceSnapshot
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ReadRecordHistoryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.applicationContext
    private val prefs = context.defaultSharedPreferences
    private val savedPrefs = prefs.all
    private val savedRecords = appDb.readRecordDao.all
    private val id = UUID.randomUUID().toString()
    private val book = Book(bookUrl = "history:$id", name = "History $id", author = "History Author")
    private var scenario: ActivityScenario<ReadRecordActivity>? = null
    private lateinit var cover: File

    @Before
    fun setUp() {
        prefs.edit().remove("readRecordSimpleLayout").remove("readRecordUseDays").commit()
        appDb.readRecordDao.clear()
        cover = File(context.cacheDir, "history-$id.png")
        Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(35, 148, 115))
            cover.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        book.coverUrl = cover.absolutePath
        book.durChapterIndex = 6
        book.durChapterTitle = "Chapter 7: Current chapter"
        book.durChapterPos = 25
        appDb.bookDao.insert(book)
        appDb.readRecordDao.insert(ReadRecord(
            deviceId = AppConst.androidId, bookName = book.name, author = book.author,
            readTime = 25 * 3600_000L, lastRead = 1000,
        ))
        appDb.readRecordDao.insert(ReadRecord(deviceId = "remote", bookName = "Archived second", readTime = 7200_000L, lastRead = 500))
        appDb.readRecordDao.insert(ReadRecord(deviceId = "remote", bookName = "Archived third", readTime = 3600_000L, lastRead = 400))
    }

    @After
    fun tearDown() {
        scenario?.close()
        appDb.bookDao.delete(book)
        appDb.readRecordDao.clear()
        appDb.readRecordDao.insert(*savedRecords.toTypedArray())
        ReadRecordCoverCache.prune()
        cover.delete()
        prefs.edit().apply {
            for (key in listOf("readRecordSimpleLayout", "readRecordUseDays")) {
                val value = savedPrefs[key]
                if (value is Boolean) putBoolean(key, value) else remove(key)
            }
        }.commit()
    }

    @Test
    fun layoutSwitchAndDayToggleRefreshSummaryAndRows() {
        launch()
        await { binding -> binding.recyclerView.adapter?.itemCount == 3 }
        scenario!!.onActivity { activity ->
            assertTrue(AppConfig.readRecordSimpleLayout)
            assertFalse(AppConfig.readRecordUseDays)
            assertTrue(activity.views.compactSummary.isVisible)
            assertEquals("28小时", activity.views.tvReadingTime.text.toString())
            select(activity, R.id.menu_simple_layout)
        }
        await { it.enhancedSummary.root.isVisible }
        scenario!!.onActivity { activity ->
            val binding = activity.views
            assertFalse(binding.compactSummary.isVisible)
            assertTrue(binding.enhancedSummary.tvBookCount.text.contains("3"))
            val row = findRow(binding, book.name)!!
            assertTrue(row.enhanced.root.isVisible)
            assertEquals(book.durChapterTitle, row.enhanced.tvChapter.text.toString())
            assertEquals("25小时", row.enhanced.tvReadingTime.text.toString())
            assertTrue(row.enhanced.tvBookName.textSize > row.enhanced.tvAuthor.textSize)
            assertTrue(row.enhanced.tvReadingTime.textSize > row.enhanced.tvLastReadTime.textSize)
            select(activity, R.id.menu_use_days)
        }
        await { it.enhancedSummary.tvTotalDuration.text.contains("1天4小时") }
        scenario!!.onActivity { activity ->
            assertEquals("1天1小时", findRow(activity.views, book.name)!!.enhanced.tvReadingTime.text.toString())
            assertNoTextOverflow(activity.views.enhancedSummary.root)
            assertNoTextOverflow(findRow(activity.views, book.name)!!.enhanced.root)
        }
        screenshot("reading-history-enhanced")
        scenario!!.recreate()
        await { it.enhancedSummary.root.isVisible && it.enhancedSummary.tvTotalDuration.text.contains("1天4小时") }
    }

    @Test
    fun newestDeviceSnapshotAndSearchKeepCorrectTotalDuration() {
        appDb.readRecordDao.insert(
            ReadRecord(deviceId = "old", bookName = "Same", author = "Old author", readTime = 400, lastRead = 100,
                lastChapterTitle = "Old chapter", lastChapterIndex = 2),
            ReadRecord(deviceId = "new", bookName = "Same", author = "New author", readTime = 200, lastRead = 200,
                lastChapterTitle = "New chapter", lastChapterIndex = 8, lastChapterPos = 33, coverUrl = "cover"),
        )
        val record = appDb.readRecordDao.search("New author").single()
        assertEquals(600L, record.readTime)
        assertEquals(200L, record.lastRead)
        assertEquals("New chapter", record.lastChapterTitle)
        assertEquals(8, record.lastChapterIndex)
        assertEquals(33, record.lastChapterPos)
        assertEquals("cover", record.coverUrl)
        assertEquals(record, appDb.readRecordDao.allShow.single { it.bookName == "Same" })
    }

    @Test
    fun snapshotUsesTheCapturedChapterIndexBeforeBookshelfTitleCatchesUp() {
        appDb.bookChapterDao.insert(BookChapter(
            bookUrl = book.bookUrl, url = "chapter:$id", index = 12, title = "Chapter 13: Captured chapter",
        ))
        ReadRecord(
            deviceId = AppConst.androidId, bookName = book.name, lastChapterIndex = 12,
            lastChapterTitle = "Stale bookshelf title", lastChapterPos = 33,
        ).saveWithCover(book)
        val saved = appDb.readRecordDao.getRecord(AppConst.androidId, book.name)!!
        assertEquals(12, saved.lastChapterIndex)
        assertEquals(33, saved.lastChapterPos)
        assertEquals("Chapter 13: Captured chapter", saved.lastChapterTitle)
    }

    @Test
    fun refreshingSnapshotCannotUndoConcurrentDurationUpdatesOrDeletion() {
        val executor = Executors.newSingleThreadExecutor()
        fun refreshDuring(change: () -> Unit) {
            lateinit var refresh: Future<*>
            appDb.runInTransaction {
                val started = CountDownLatch(1)
                refresh = executor.submit {
                    started.countDown()
                    book.saveReadRecordSnapshot()
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                change()
            }
            refresh.get(10, TimeUnit.SECONDS)
        }
        try {
            val latest = appDb.readRecordDao.getRecord(AppConst.androidId, book.name)!!
                .copy(readTime = 30 * 3600_000L, lastRead = 5000)
            refreshDuring { appDb.readRecordDao.insert(latest) }
            val refreshed = appDb.readRecordDao.getRecord(AppConst.androidId, book.name)!!
            assertEquals(latest.readTime, refreshed.readTime)
            assertEquals(latest.lastRead, refreshed.lastRead)
            assertEquals(book.durChapterTitle, refreshed.lastChapterTitle)

            refreshDuring { appDb.readRecordDao.deleteByName(book.name) }
            assertNull(appDb.readRecordDao.getRecord(AppConst.androidId, book.name))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun coverDownloadMustDecodeBeforeItReplacesTheOriginalAddress() {
        val invalid = File(context.cacheDir, "invalid-cover-$id.html").apply { writeText("<html>not an image</html>") }
        try {
            val record = ReadRecord(deviceId = AppConst.androidId, bookName = book.name, coverUrl = invalid.absolutePath)
            appDb.readRecordDao.insert(record)
            runBlocking { ReadRecordCoverCache.request(record)!!.join() }
            assertEquals(invalid.absolutePath, appDb.readRecordDao.getRecord(AppConst.androidId, book.name)!!.coverUrl)
            record.coverUrl = cover.absolutePath
            appDb.readRecordDao.insert(record)
            runBlocking { ReadRecordCoverCache.request(record)!!.join() }
            val saved = appDb.readRecordDao.getRecord(AppConst.androidId, book.name)!!
            assertNotEquals(cover.absolutePath, saved.coverUrl)
            assertArrayEquals(cover.readBytes(), File(saved.coverUrl!!).readBytes())
        } finally { invalid.delete() }
    }

    @Test
    fun deletingBookRetainsOwnedCoverAndDeletingHistoryRemovesOnlyItsCopy() {
        book.saveReadRecordSnapshot()
        val stored = appDb.readRecordDao.getRecord(AppConst.androidId, book.name)!!
        val retained = File(stored.coverUrl!!)
        assertNotEquals(cover.absolutePath, retained.absolutePath)
        assertArrayEquals(cover.readBytes(), retained.readBytes())
        appDb.bookDao.delete(book)
        cover.delete()
        assertTrue(retained.isFile)
        AppConfig.readRecordSimpleLayout = false
        launch()
        await { findRow(it, book.name)?.enhanced?.tvChapter?.text == book.durChapterTitle }
        scenario!!.onActivity { activity ->
            findRow(activity.views, book.name)!!.enhanced.ivRemove.performClick()
        }
        instrumentation.waitForIdleSync()
        onView(withId(android.R.id.button1)).perform(click())
        await { it.recyclerView.adapter?.itemCount == 2 }
        assertFalse(retained.exists())
        assertTrue(appDb.readRecordDao.all.any { it.bookName == "Archived second" })
    }

    @Test
    fun oldPreferencesRestoreSimpleLayoutAndCoversDefaultToExcluded() {
        val directory = File(context.cacheDir, "history-preferences-$id").apply { mkdirs() }
        try {
            AppConfig.readRecordSimpleLayout = false
            AppConfig.readRecordUseDays = true
            writePreferenceSnapshot(context, directory.absolutePath, "config") { putBoolean("enableReadRecord", true) }
            File(directory, "readRecord.json").writeText(GSON.toJson(listOf(ReadRecord(
                deviceId = "", bookName = book.name, readTime = 30 * 3600_000L, lastRead = 2000,
                lastChapterTitle = "Restored chapter", lastChapterIndex = 12, lastChapterPos = 44,
            ))))
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.absolutePath) }
            assertTrue(AppConfig.readRecordSimpleLayout)
            assertFalse(AppConfig.readRecordUseDays)
            val restored = appDb.readRecordDao.getRecord(AppConst.androidId, book.name)!!
            assertEquals(30 * 3600_000L, restored.readTime)
            assertEquals("Restored chapter", restored.lastChapterTitle)
            assertEquals(12, restored.lastChapterIndex)
            assertEquals(44, restored.lastChapterPos)
            assertNull(appDb.readRecordDao.getRecord("", book.name))
            val previous = BackupConfig.ignoreConfig.remove(BackupConfig.readRecordCoverContentKey)
            try {
                assertFalse(BackupConfig.contentIsEnabled(BackupConfig.readRecordCoverContentKey))
            } finally {
                if (previous != null) BackupConfig.ignoreConfig[BackupConfig.readRecordCoverContentKey] = previous
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun enhancedLayoutFitsNarrowScreenAndLargerText() {
        shell("wm size 720x1280")
        shell("wm density 360")
        shell("settings put system font_scale 1.3")
        try {
            AppConfig.readRecordSimpleLayout = false
            launch()
            await { findRow(it, book.name) != null }
            scenario!!.onActivity { activity ->
                assertNoTextOverflow(activity.views.enhancedSummary.root)
                assertNoTextOverflow(findRow(activity.views, book.name)!!.enhanced.root)
            }
            screenshot("reading-history-narrow-large-text")
        } finally {
            scenario?.close()
            scenario = null
            shell("settings put system font_scale 1.0")
            shell("wm density reset")
            shell("wm size reset")
        }
    }

    private fun launch() { scenario = ActivityScenario.launch(ReadRecordActivity::class.java) }

    private val ReadRecordActivity.views: ActivityReadRecordBinding
        get() = ActivityReadRecordBinding.bind(findViewById<ViewGroup>(android.R.id.content).getChildAt(0))

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        instrumentation.waitForIdleSync()
    }

    private fun select(activity: ReadRecordActivity, id: Int) {
        val menu: Menu = PopupMenu(activity, activity.views.titleBar).menu
        activity.menuInflater.inflate(R.menu.book_read_record, menu)
        activity.onCompatOptionsItemSelected(menu.findItem(id))
    }

    private fun findRow(binding: ActivityReadRecordBinding, name: String): ItemReadRecordDisplayBinding? {
        for (index in 0 until binding.recyclerView.childCount) {
            val row = ItemReadRecordDisplayBinding.bind(binding.recyclerView.getChildAt(index))
            if (row.enhanced.tvBookName.text.toString() == name || row.compact.tvBookName.text.toString() == name) return row
        }
        return null
    }

    private fun await(predicate: (ActivityReadRecordBinding) -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < end) {
            instrumentation.waitForIdleSync()
            var ready = false
            scenario!!.onActivity { ready = predicate(it.views) }
            if (ready) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Reading history did not reach the expected state")
    }

    private fun assertNoTextOverflow(view: View) {
        if (!view.isVisible) return
        if (view is TextView && view.text.isNotEmpty()) {
            assertTrue("Text width: ${view.text}", view.width > view.paddingLeft + view.paddingRight)
            assertTrue("Text height: ${view.text}", view.height >= view.layout.height + view.paddingTop + view.paddingBottom)
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) assertNoTextOverflow(view.getChildAt(i))
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream()
                .use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
