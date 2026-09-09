package io.legado.app.ui.about

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageView
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.replaceBookAfterSourceChange
import io.legado.app.data.entities.saveReadRecordSnapshot
import io.legado.app.data.entities.saveWithCover
import io.legado.app.databinding.ActivityReadRecordBinding
import io.legado.app.databinding.ItemReadRecordDisplayBinding
import io.legado.app.help.book.ReadRecordCoverCache
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.Restore
import io.legado.app.help.storage.writePreferenceSnapshot
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.ThemeStorePrefKeys
import io.legado.app.ui.book.read.ReadBookActivity
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
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ReadRecordHistoryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.applicationContext
    private val prefs = context.defaultSharedPreferences
    private val savedPrefs = prefs.all
    private val savedSort = LocalConfig.all["readRecordSort"]
    private val savedRecords = appDb.readRecordDao.all
    private val id = UUID.randomUUID().toString()
    private val book = Book(bookUrl = "history:$id", name = "History $id", author = "History Author")
    private var scenario: ActivityScenario<ReadRecordActivity>? = null
    private lateinit var cover: File

    @Before
    fun setUp() {
        prefs.edit().remove("readRecordSimpleLayout").remove("readRecordUseDays")
            .remove("readRecordShowSeconds").commit()
        LocalConfig.edit().putInt("readRecordSort", 1).commit()
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
            for (key in listOf("readRecordSimpleLayout", "readRecordUseDays", "readRecordShowSeconds")) {
                val value = savedPrefs[key]
                if (value is Boolean) putBoolean(key, value) else remove(key)
            }
        }.commit()
        LocalConfig.edit().apply {
            if (savedSort is Int) putInt("readRecordSort", savedSort) else remove("readRecordSort")
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
            assertRecordLayout(findRow(activity.views, book.name)!!)
        }
        screenshot("reading-history-enhanced")
        scenario!!.recreate()
        await { it.enhancedSummary.root.isVisible && it.enhancedSummary.tvTotalDuration.text.contains("1天4小时") }
    }

    @Test
    fun emptyCoversFollowLightDarkAndCustomBackgrounds() {
        val themePrefs = ThemeStore.prefs(context)
        val backgroundKey = ThemeStorePrefKeys.KEY_BACKGROUND_COLOR
        val savedBackground = themePrefs.all[backgroundKey]
        val savedMode = prefs.getString(PreferKey.themeMode, null)
        val savedNightMode = AppCompatDelegate.getDefaultNightMode()
        fun centerColor(image: ImageView): Int {
            val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            return try {
                image.draw(Canvas(bitmap))
                bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            } finally { bitmap.recycle() }
        }
        try {
            AppConfig.readRecordSimpleLayout = false
            for ((name, background) in listOf("light" to Color.rgb(245, 245, 245),
                "dark" to Color.rgb(32, 32, 32), "custom" to Color.rgb(231, 214, 185))) {
                val dark = name == "dark"
                prefs.edit().putString(PreferKey.themeMode, if (dark) "2" else "1").commit()
                themePrefs.edit().putInt(backgroundKey, background).commit()
                instrumentation.runOnMainSync {
                    AppCompatDelegate.setDefaultNightMode(if (dark) AppCompatDelegate.MODE_NIGHT_YES
                        else AppCompatDelegate.MODE_NIGHT_NO)
                }
                launch()
                await { binding ->
                    findRow(binding, "Archived second")?.enhanced?.ivCover?.drawable != null &&
                        coverColor(binding.enhancedSummary.coverFirst) == Color.rgb(35, 148, 115) &&
                        findRow(binding, book.name)?.enhanced?.ivCover?.let(::coverColor) == Color.rgb(35, 148, 115)
                }
                scenario!!.onActivity { activity ->
                    val binding = activity.views
                    val missing = findRow(binding, "Archived second")!!.enhanced.ivCover
                    val fill = centerColor(missing)
                    assertEquals("Row and summary use the same empty cover", fill,
                        centerColor(binding.enhancedSummary.coverSecond))
                    assertNotEquals("The cover must remain distinguishable", background, fill)
                    assertNotEquals("Dark/custom covers must not stay white", Color.WHITE, fill)
                    for (channel in listOf<(Int) -> Int>(Color::red, Color::green, Color::blue)) {
                        assertTrue("Cover stays close to its background", kotlin.math.abs(channel(fill) - channel(background)) <= 21)
                    }
                    assertEquals("Real covers retain their original pixels", Color.rgb(35, 148, 115),
                        centerColor(findRow(binding, book.name)!!.enhanced.ivCover))
                }
                screenshot("reading-history-covers-$name")
                scenario!!.close()
                scenario = null
            }
        } finally {
            scenario?.close()
            scenario = null
            themePrefs.edit().apply {
                if (savedBackground is Int) putInt(backgroundKey, savedBackground) else remove(backgroundKey)
            }.commit()
            prefs.edit().apply {
                if (savedMode != null) putString(PreferKey.themeMode, savedMode) else remove(PreferKey.themeMode)
            }.commit()
            instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(savedNightMode) }
        }
    }

    @Test
    fun secondsToggleUpdatesBothLayoutsWithoutChangingStoredDuration() {
        val record = appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author)!!
        record.readTime += 123_000L
        appDb.readRecordDao.update(record)
        val original = appDb.readRecordDao.all.toSet()
        launch()
        await { findRow(it, book.name)?.compact?.tvReadingTime?.text == "25小时2分钟3秒" }
        scenario!!.onActivity {
            assertTrue(AppConfig.readRecordShowSeconds)
            select(it, R.id.menu_show_seconds)
        }
        await { it.tvReadingTime.text == "28小时2分钟" &&
            findRow(it, book.name)?.compact?.tvReadingTime?.text == "25小时2分钟" }
        scenario!!.onActivity { select(it, R.id.menu_simple_layout) }
        await { it.enhancedSummary.root.isVisible &&
            it.enhancedSummary.tvTotalDuration.text.contains("28小时2分钟") &&
            findRow(it, book.name)?.enhanced?.tvReadingTime?.text == "25小时2分钟" }
        screenshot("reading-history-minutes")
        scenario!!.onActivity { select(it, R.id.menu_use_days) }
        await { it.enhancedSummary.tvTotalDuration.text.contains("1天4小时2分钟") }
        scenario!!.recreate()
        await { findRow(it, book.name)?.enhanced?.tvReadingTime?.text == "1天1小时2分钟" }
        scenario!!.onActivity {
            assertFalse(AppConfig.readRecordShowSeconds)
            select(it, R.id.menu_show_seconds)
        }
        await { it.enhancedSummary.tvTotalDuration.text.contains("1天4小时2分钟3秒") &&
            findRow(it, book.name)?.enhanced?.tvReadingTime?.text == "1天1小时2分钟3秒" }
        assertEquals(original, appDb.readRecordDao.all.toSet())
    }

    @Test
    fun largeHistoryOpensAndFiltersWithoutWritingBookshelfSnapshots() {
        appDb.readRecordDao.insert(*(0 until 6372).map { index ->
            ReadRecord(deviceId = "history-device", bookName = "Archived $id $index",
                author = "Author $index", readTime = index + 1L, lastRead = index + 1L,
                lastChapterTitle = "Saved chapter $index", lastChapterIndex = index,
                lastChapterPos = index % 10)
        }.toTypedArray())
        val before = appDb.readRecordDao.all.toSet()
        AppConfig.readRecordSimpleLayout = false
        val start = SystemClock.elapsedRealtime()
        launch()
        await { it.recyclerView.adapter?.itemCount == 6375 && findRow(it, book.name) != null }
        val elapsed = SystemClock.elapsedRealtime() - start
        assertTrue("First history rows took ${elapsed}ms", elapsed < 8000)
        scenario!!.onActivity { activity ->
            assertEquals(book.durChapterTitle, findRow(activity.views, book.name)!!.enhanced.tvChapter.text.toString())
            assertEquals(context.getString(R.string.read_record_total_duration,
                formatDuring(before.sumOf { it.readTime })), activity.views.enhancedSummary.tvTotalDuration.text.toString())
        }
        screenshot("reading-history-6375-records")
        scenario!!.onActivity { activity ->
            activity.views.titleBar.findViewById<SearchView>(R.id.search_view).setQuery(book.name, false)
        }
        await { it.recyclerView.adapter?.itemCount == 1 && findRow(it, book.name) != null }
        scenario!!.onActivity { activity ->
            assertEquals(context.getString(R.string.read_record_total_duration,
                formatDuring(before.sumOf { it.readTime })), activity.views.enhancedSummary.tvTotalDuration.text.toString())
            select(activity, R.id.menu_simple_layout)
            select(activity, R.id.menu_use_days)
            select(activity, R.id.menu_sort_name)
        }
        await { it.compactSummary.isVisible && it.recyclerView.adapter?.itemCount == 1 }
        scenario!!.onActivity { activity ->
            activity.views.titleBar.findViewById<SearchView>(R.id.search_view).setQuery("", false)
        }
        await { it.recyclerView.adapter?.itemCount == 6375 }
        assertEquals("Displaying, filtering and sorting must not rewrite persisted history", before,
            appDb.readRecordDao.all.toSet())
        println("History first display: rows=6375 elapsedMs=$elapsed")
    }

    @Test
    fun newestDeviceSnapshotAndSearchKeepCorrectTotalDuration() {
        appDb.readRecordDao.insert(
            ReadRecord(deviceId = "old", bookName = "Same", author = "New author", readTime = 400, lastRead = 100,
                lastChapterTitle = "Old chapter", lastChapterIndex = 2),
            ReadRecord(deviceId = "new", bookName = "Same", author = "New author", readTime = 200, lastRead = 200,
                lastChapterTitle = "New chapter", lastChapterIndex = 8, lastChapterPos = 33, coverUrl = "cover"),
            ReadRecord(deviceId = "new", bookName = "Same", author = "Other author", readTime = 900, lastRead = 300,
                lastChapterTitle = "Other chapter", lastChapterIndex = 19, coverUrl = "other-cover"),
        )
        val record = appDb.readRecordDao.search("New author").single()
        assertEquals(600L, record.readTime)
        assertEquals(200L, record.lastRead)
        assertEquals("New chapter", record.lastChapterTitle)
        assertEquals(8, record.lastChapterIndex)
        assertEquals(33, record.lastChapterPos)
        assertEquals("cover", record.coverUrl)
        assertEquals(record, appDb.readRecordDao.allShow.single { it.bookName == "Same" && it.author == "New author" })
        assertEquals(900L, appDb.readRecordDao.search("Other author").single().readTime)
        assertEquals(2, appDb.readRecordDao.search("Same").size)
    }

    @Test
    fun sameNameAuthorsKeepSeparateRowsCoversChaptersAndReaderRoutes() {
        val otherColor = Color.rgb(190, 60, 40)
        val otherCover = File(context.cacheDir, "history-other-$id.png")
        Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(otherColor)
            otherCover.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val other = book.copy(
            bookUrl = "history-other:$id", author = "Other History Author", coverUrl = otherCover.absolutePath,
            durChapterTitle = "Other author's chapter", durChapterIndex = 20,
            durChapterTime = book.durChapterTime + 1,
        )
        val opened = AtomicReference<Intent?>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className != ReadBookActivity::class.java.name) return null
                opened.set(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            appDb.bookDao.insert(other)
            appDb.readRecordDao.clear()
            appDb.readRecordDao.insert(
                ReadRecord(deviceId = AppConst.androidId, bookName = book.name, author = book.author,
                    readTime = 25 * 3600_000L, lastRead = 1000),
                ReadRecord(deviceId = AppConst.androidId, bookName = other.name, author = other.author,
                    readTime = 12 * 3600_000L, lastRead = 2000),
            )
            book.saveReadRecordSnapshot()
            other.saveReadRecordSnapshot()
            val firstStored = appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author)!!
            val otherStored = appDb.readRecordDao.getRecord(AppConst.androidId, other.name, other.author)!!
            val retained = File(firstStored.coverUrl!!)
            val otherRetained = File(otherStored.coverUrl!!)
            assertArrayEquals(cover.readBytes(), retained.readBytes())
            assertArrayEquals(otherCover.readBytes(), otherRetained.readBytes())
            assertNotEquals(retained.absolutePath, otherRetained.absolutePath)
            assertEquals(book.durChapterTitle, firstStored.lastChapterTitle)
            assertEquals(other.durChapterTitle, otherStored.lastChapterTitle)

            AppConfig.readRecordSimpleLayout = false
            launch()
            await { binding ->
                val first = findRow(binding, book.name, book.author)?.enhanced
                val second = findRow(binding, other.name, other.author)?.enhanced
                binding.recyclerView.adapter?.itemCount == 2 &&
                    first?.tvChapter?.text == book.durChapterTitle &&
                    second?.tvChapter?.text == other.durChapterTitle &&
                    first?.ivCover?.let(::coverColor) == Color.rgb(35, 148, 115) &&
                    second?.ivCover?.let(::coverColor) == otherColor
            }
            scenario!!.onActivity { activity ->
                assertEquals("25小时", findRow(activity.views, book.name, book.author)!!.enhanced.tvReadingTime.text.toString())
                assertEquals("12小时", findRow(activity.views, other.name, other.author)!!.enhanced.tvReadingTime.text.toString())
            }
            for (selected in listOf(book, other)) {
                scenario!!.onActivity { activity ->
                    assertTrue(findRow(activity.views, selected.name, selected.author)!!.root.performClick())
                }
                await { opened.get() != null }
                assertEquals(selected.bookUrl, opened.getAndSet(null)!!.getStringExtra("bookUrl"))
            }
            screenshot("reading-history-same-name-authors")
            scenario!!.onActivity { select(it, R.id.menu_simple_layout) }
            await { findRow(it, book.name, book.author)?.compact?.root?.isVisible == true &&
                findRow(it, other.name, other.author)?.compact?.root?.isVisible == true }
            scenario!!.onActivity { activity ->
                assertEquals(context.getString(R.string.author_show, book.author),
                    findRow(activity.views, book.name, book.author)!!.compact.tvAuthor.text.toString())
                assertEquals(context.getString(R.string.author_show, other.author),
                    findRow(activity.views, other.name, other.author)!!.compact.tvAuthor.text.toString())
                assertTrue(findRow(activity.views, other.name, other.author)!!.root.performClick())
            }
            await { opened.get() != null }
            assertEquals(other.bookUrl, opened.getAndSet(null)!!.getStringExtra("bookUrl"))
            scenario!!.onActivity { select(it, R.id.menu_simple_layout) }
            await { findRow(it, book.name, book.author)?.enhanced?.root?.isVisible == true }
            scenario!!.onActivity { activity ->
                assertTrue(findRow(activity.views, book.name, book.author)!!.enhanced.ivRemove.performClick())
            }
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
            await { it.recyclerView.adapter?.itemCount == 1 && findRow(it, other.name, other.author) != null }
            assertNull(appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author))
            assertEquals(otherStored, appDb.readRecordDao.getRecord(AppConst.androidId, other.name, other.author))
            assertFalse(retained.exists())
            assertTrue(otherRetained.isFile)
            assertArrayEquals(otherCover.readBytes(), otherRetained.readBytes())
        } finally {
            instrumentation.removeMonitor(monitor)
            scenario?.close()
            scenario = null
            appDb.bookDao.delete(other)
            otherCover.delete()
        }
    }

    @Test
    fun snapshotUsesTheCapturedChapterIndexBeforeBookshelfTitleCatchesUp() {
        appDb.bookChapterDao.insert(BookChapter(
            bookUrl = book.bookUrl, url = "chapter:$id", index = 12, title = "Chapter 13: Captured chapter",
        ))
        ReadRecord(
            deviceId = AppConst.androidId, bookName = book.name, author = book.author, lastChapterIndex = 12,
            lastChapterTitle = "Stale bookshelf title", lastChapterPos = 33,
        ).saveWithCover(book)
        val saved = appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author)!!
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
            val latest = appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author)!!
                .copy(readTime = 30 * 3600_000L, lastRead = 5000)
            refreshDuring { appDb.readRecordDao.insert(latest) }
            val refreshed = appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author)!!
            assertEquals(latest.readTime, refreshed.readTime)
            assertEquals(latest.lastRead, refreshed.lastRead)
            assertEquals(book.durChapterTitle, refreshed.lastChapterTitle)

            refreshDuring { appDb.readRecordDao.deleteByBook(book.name, book.author) }
            assertNull(appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun coverDownloadMustDecodeBeforeItReplacesTheOriginalAddress() {
        val invalid = File(context.cacheDir, "invalid-cover-$id.html").apply { writeText("<html>not an image</html>") }
        try {
            val record = ReadRecord(deviceId = AppConst.androidId, bookName = book.name, author = book.author,
                coverUrl = invalid.absolutePath)
            appDb.readRecordDao.insert(record)
            runBlocking { ReadRecordCoverCache.request(record)!!.join() }
            assertEquals(invalid.absolutePath, appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author)!!.coverUrl)
            record.coverUrl = cover.absolutePath
            appDb.readRecordDao.insert(record)
            runBlocking { ReadRecordCoverCache.request(record)!!.join() }
            val saved = appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author)!!
            assertNotEquals(cover.absolutePath, saved.coverUrl)
            assertArrayEquals(cover.readBytes(), File(saved.coverUrl!!).readBytes())
        } finally { invalid.delete() }
    }

    @Test
    fun deletingBookRetainsOwnedCoverAndDeletingHistoryRemovesOnlyItsCopy() {
        // The real deletion boundary must retain the snapshot without first opening history.
        book.delete()
        val stored = appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author)!!
        val retained = File(stored.coverUrl!!)
        assertNotEquals(cover.absolutePath, retained.absolutePath)
        assertArrayEquals(cover.readBytes(), retained.readBytes())
        cover.delete()
        assertTrue(retained.isFile)
        AppConfig.readRecordSimpleLayout = false
        launch()
        await { findRow(it, book.name)?.enhanced?.tvChapter?.text == book.durChapterTitle }
        scenario!!.onActivity { activity ->
            findRow(activity.views, book.name)!!.enhanced.ivRemove.performClick()
        }
        instrumentation.waitForIdleSync()
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
        await { it.recyclerView.adapter?.itemCount == 2 }
        assertFalse(retained.exists())
        assertTrue(appDb.readRecordDao.all.any { it.bookName == "Archived second" })
    }

    @Test
    fun changingSourceRetainsHistoryBeforeRemovingTheOldBookshelfEntry() {
        val replacement = book.copy(bookUrl = "history-new:$id", coverUrl = null,
            durChapterTitle = "New source chapter")
        val before = appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author)!!
        try {
            replaceBookAfterSourceChange(book, replacement, emptyList(), clearActiveReader = false)
            assertNull(appDb.bookDao.getBook(book.bookUrl))
            assertNotNull(appDb.bookDao.getBook(replacement.bookUrl))
            val saved = appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author)!!
            assertEquals(before.readTime, saved.readTime)
            assertEquals(before.lastRead, saved.lastRead)
            assertEquals(book.durChapterTitle, saved.lastChapterTitle)
            assertEquals(book.durChapterIndex, saved.lastChapterIndex)
            assertEquals(book.durChapterPos, saved.lastChapterPos)
            assertArrayEquals(cover.readBytes(), File(saved.coverUrl!!).readBytes())
            AppConfig.readRecordSimpleLayout = false
            launch()
            await { findRow(it, book.name)?.enhanced?.tvChapter?.text == replacement.durChapterTitle }
            assertEquals("Showing the new bookshelf chapter must not replace the saved snapshot", saved,
                appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author))
        } finally { appDb.bookDao.delete(replacement) }
    }

    @Test
    fun oldPreferencesRestoreSimpleLayoutAndCoversDefaultToExcluded() {
        val directory = File(context.cacheDir, "history-preferences-$id").apply { mkdirs() }
        try {
            AppConfig.readRecordSimpleLayout = false
            AppConfig.readRecordUseDays = true
            AppConfig.readRecordShowSeconds = false
            writePreferenceSnapshot(context, directory.absolutePath, "config") { putBoolean("enableReadRecord", true) }
            File(directory, "readRecord.json").writeText(GSON.toJson(listOf(ReadRecord(
                deviceId = "", bookName = book.name, readTime = 30 * 3600_000L, lastRead = 2000,
                lastChapterTitle = "Restored chapter", lastChapterIndex = 12, lastChapterPos = 44,
            ))))
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.absolutePath) }
            assertTrue(AppConfig.readRecordSimpleLayout)
            assertFalse(AppConfig.readRecordUseDays)
            assertTrue(AppConfig.readRecordShowSeconds)
            val restored = appDb.readRecordDao.getRecord(AppConst.androidId, book.name, "")!!
            assertEquals(30 * 3600_000L, restored.readTime)
            assertEquals("Restored chapter", restored.lastChapterTitle)
            assertEquals(12, restored.lastChapterIndex)
            assertEquals(44, restored.lastChapterPos)
            assertNull(appDb.readRecordDao.getRecord("", book.name, ""))
            val knownAuthor = appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author)!!
            assertEquals(25 * 3600_000L, knownAuthor.readTime)
            assertEquals(1000L, knownAuthor.lastRead)
            assertEquals(setOf("", book.author), appDb.readRecordDao.allShow
                .filter { it.bookName == book.name }.map { it.author }.toSet())
            val previous = BackupConfig.ignoreConfig.remove(BackupConfig.readRecordCoverContentKey)
            try {
                assertFalse(BackupConfig.contentIsEnabled(BackupConfig.readRecordCoverContentKey))
            } finally {
                if (previous != null) BackupConfig.ignoreConfig[BackupConfig.readRecordCoverContentKey] = previous
            }
            writePreferenceSnapshot(context, directory.absolutePath, "config") {
                putBoolean("readRecordShowSeconds", false)
            }
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.absolutePath) }
            assertFalse(AppConfig.readRecordShowSeconds)
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
                assertRecordLayout(findRow(activity.views, book.name)!!)
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

    private fun findRow(binding: ActivityReadRecordBinding, name: String, author: String? = null): ItemReadRecordDisplayBinding? {
        for (index in 0 until binding.recyclerView.childCount) {
            val row = ItemReadRecordDisplayBinding.bind(binding.recyclerView.getChildAt(index))
            val sameName = row.enhanced.tvBookName.text.toString() == name || row.compact.tvBookName.text.toString() == name
            val sameAuthor = author == null || row.enhanced.tvAuthor.text.toString() == author ||
                row.compact.tvAuthor.text.toString() == context.getString(R.string.author_show, author)
            if (sameName && sameAuthor) return row
        }
        return null
    }

    private fun coverColor(image: ImageView): Int? {
        val bitmap = (image.drawable as? BitmapDrawable)?.bitmap ?: return null
        val readable = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        return try { readable.getPixel(readable.width / 2, readable.height / 2) } finally { readable.recycle() }
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

    private fun assertRecordLayout(row: ItemReadRecordDisplayBinding) {
        with(row.enhanced) {
            assertNoTextOverflow(root)
            val views = listOf(ivCover, tvBookName, tvAuthor, tvChapter, tvReadingTime, tvLastReadTime, ivRemove)
            views.forEachIndexed { index, first ->
                val firstBounds = Rect().also(first::getHitRect)
                views.drop(index + 1).forEach { second ->
                    val secondBounds = Rect().also(second::getHitRect)
                    assertFalse("Overlapping history fields: ${first.id}, ${second.id}",
                        Rect.intersects(firstBounds, secondBounds))
                }
            }
        }
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
