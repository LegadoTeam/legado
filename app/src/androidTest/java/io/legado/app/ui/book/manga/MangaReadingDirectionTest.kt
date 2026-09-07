package io.legado.app.ui.book.manga

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.SeekBar
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.ActivityMangaBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.Restore
import io.legado.app.help.storage.readPreferenceSnapshot
import io.legado.app.help.storage.writePreferenceSnapshot
import io.legado.app.model.ReadManga
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.entities.ReaderLoading
import io.legado.app.ui.book.manga.recyclerview.MangaAdapter
import io.legado.app.ui.widget.TitleBar
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.findCenterViewPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class MangaReadingDirectionTest {
    @get:Rule
    val testName = TestName()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.applicationContext
    private val accessibilityFlags = instrumentation.uiAutomation.serviceInfo.flags
    private val preferences = context.defaultSharedPreferences
    private val savedPreferences = HashMap(preferences.all)
    private val source = BookSource(
        bookSourceUrl = "https://manga-${UUID.randomUUID()}.invalid",
        bookSourceName = "Manga direction fixture",
        bookSourceType = BookSourceType.image,
    )
    private val book = Book(
        bookUrl = "${source.bookSourceUrl}/book",
        tocUrl = "${source.bookSourceUrl}/toc",
        origin = source.bookSourceUrl,
        name = "Manga direction ${UUID.randomUUID()}",
        type = BookType.image,
        totalChapterNum = 3,
        durChapterIndex = 1,
        durChapterPos = 1,
        canUpdate = false,
    )
    private var scenario: ActivityScenario<ReadMangaActivity>? = null
    private var lastInput = "launch"
    private val ReadMangaActivity.ui: ActivityMangaBinding
        get() = ActivityMangaBinding.bind(findViewById<ViewGroup>(android.R.id.content).getChildAt(0))

    @Before
    fun setUp() {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        assertTrue(preferences.edit()
            .remove(PreferKey.mangaRightToLeft)
            .putBoolean(PreferKey.enableMangaHorizontalScroll, true)
            .putBoolean(PreferKey.hideMangaTitle, true)
            .putBoolean(PreferKey.disableClickScroll, false)
            .putBoolean(PreferKey.disableMangaScale, true)
            .putBoolean(PreferKey.disableHorizontalPageSnap, false)
            .putBoolean(PreferKey.disableMangaPageAnim, false)
            .putBoolean(PreferKey.enableMangaEInk, false)
            .putBoolean(PreferKey.enableMangaGray, false)
            .putBoolean(PreferKey.enableReadRecord, false)
            .putBoolean(PreferKey.syncBookProgress, false)
            .putBoolean(PreferKey.syncBookProgressPlus, false)
            .putInt(PreferKey.mangaPreDownloadNum, 0)
            .putInt(PreferKey.preDownloadNum, 0)
            .commit())
        appDb.bookSourceDao.insert(source)
        appDb.bookDao.insert(book)
        repeat(3) { chapterIndex ->
            val chapter = BookChapter(bookUrl = book.bookUrl,
                url = "${source.bookSourceUrl}/chapter/$chapterIndex",
                title = "Chapter ${chapterIndex + 1}", index = chapterIndex)
            appDb.bookChapterDao.insert(chapter)
            val content = (0 until 4).joinToString("\n") { pageIndex ->
                val bitmap = Bitmap.createBitmap(240, 480, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(pageColor(chapterIndex, pageIndex))
                    val bytes = ByteArrayOutputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        it.toByteArray()
                    }
                    BookHelp.writeImage(book, imageUrl(chapterIndex, pageIndex), bytes)
                } finally {
                    bitmap.recycle()
                }
                "<img src=\"${imageUrl(chapterIndex, pageIndex)}\">"
            }
            assertTrue(BookHelp.saveContent(source, book, chapter, content))
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
        // Drain queued progress saves before removing this test's book.
        ReadManga.executor.submit {}.get(30, TimeUnit.SECONDS)
        instrumentation.runOnMainSync {
            if (ReadManga.book?.bookUrl == book.bookUrl) {
                ReadManga.clearMangaChapter()
                ReadManga.book = null
                ReadManga.bookSource = null
            }
        }
        BookHelp.clearCache(book)
        appDb.bookChapterDao.delByBook(book.bookUrl)
        appDb.bookDao.delete(book)
        appDb.bookSourceDao.delete(source)
        assertTrue(preferences.edit().clear().apply {
            savedPreferences.forEach { (key, value) -> putValue(key, value) }
        }.commit())
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = accessibilityFlags
        }
    }

    @Test
    fun loadingOldPagesCannotOverwriteRequestedChapterAndPosition() {
        launchReader()
        for (horizontal in listOf(true, false)) {
            if (!horizontal) {
                toggle(R.id.menu_enable_horizontal_scroll)
                awaitPage(2, 2)
            }
            val chapter = if (horizontal) 2 else 0
            val page = if (horizontal) 2 else 1
            scenario!!.onActivity { activity ->
                val recycler = activity.ui.recyclerView
                val oldPage = visiblePage(activity)
                ViewModelProvider(activity)[ReadMangaViewModel::class.java].openChapter(chapter, page)
                assertEquals(View.VISIBLE, activity.ui.flLoading.visibility)
                // Force a real old-list scroll before the new content can commit on the main thread.
                recycler.scrollBy(if (horizontal) recycler.width else 0,
                    if (horizontal) 0 else recycler.height / 2)
                assertTrue("The old visible page must actually move", oldPage != visiblePage(activity))
                assertEquals("Old scroll must not replace the requested chapter", chapter, ReadManga.durChapterIndex)
                assertEquals("Old scroll must not replace the requested page", page, ReadManga.durChapterPos)
            }
            awaitPage(chapter, page)
        }
        screenshot("manga-loading-preserves-requested-position")
    }

    @Test
    fun horizontalDefaultKeepsLeftToRightGesturesTapsAndKeys() {
        assertFalse(AppConfig.mangaRightToLeft)
        launchReader()
        assertLayout(horizontal = true, rightToLeft = false)
        swipe(0.85f, 0.5f, 0.15f, 0.5f)
        awaitPage(1, 2)
        swipe(0.15f, 0.5f, 0.85f, 0.5f)
        awaitPage(1, 1)
        tap(0.84f, 0.82f)
        awaitPage(1, 2)
        tap(0.16f, 0.82f)
        awaitPage(1, 1)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitPage(1, 2)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitPage(1, 1)
        assertLogicalKeys()
        screenshot("manga-direction-ltr")
    }

    @Test
    fun rightToLeftMovesRealPagesAcrossChaptersWithGesturesTapsAndKeys() {
        AppConfig.mangaRightToLeft = true
        launchReader()
        assertLayout(horizontal = true, rightToLeft = true)
        swipe(0.15f, 0.5f, 0.85f, 0.5f)
        awaitPage(1, 2)
        swipe(0.85f, 0.5f, 0.15f, 0.5f)
        awaitPage(1, 1)
        tap(0.16f, 0.82f)
        awaitPage(1, 2)
        tap(0.84f, 0.82f)
        awaitPage(1, 1)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitPage(1, 2)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitPage(1, 1)
        assertLogicalKeys()
        key(KeyEvent.KEYCODE_PAGE_DOWN)
        awaitPage(1, 2)
        key(KeyEvent.KEYCODE_PAGE_DOWN)
        awaitPage(1, 3)
        swipe(0.15f, 0.5f, 0.85f, 0.5f)
        awaitPage(2, 0)
        assertLayout(horizontal = true, rightToLeft = true)
        swipe(0.85f, 0.5f, 0.15f, 0.5f)
        awaitPage(1, 3)
        chapterButton(R.id.tv_next)
        awaitPage(2, 0)
        chapterButton(R.id.tv_pre)
        awaitPage(1, 0)
        screenshot("manga-direction-rtl")
    }

    @Test
    fun menuToggleKeepsDisplayedPageAndRecreationAndReopenRestoreProgress() {
        launchReader()
        key(KeyEvent.KEYCODE_PAGE_DOWN)
        awaitPage(1, 2)
        toggle(R.id.menu_manga_right_to_left)
        assertTrue(AppConfig.mangaRightToLeft)
        awaitPage(1, 2)
        assertLayout(horizontal = true, rightToLeft = true)
        toggle(R.id.menu_manga_right_to_left)
        assertFalse(AppConfig.mangaRightToLeft)
        awaitPage(1, 2)
        toggle(R.id.menu_manga_right_to_left)
        awaitPage(1, 2)
        scenario!!.recreate()
        awaitPage(1, 2)
        assertLayout(horizontal = true, rightToLeft = true)
        scenario!!.close()
        scenario = null
        waitUntil("persisted chapter and page") {
            appDb.bookDao.getBook(book.bookUrl)?.let {
                it.durChapterIndex == 1 && it.durChapterPos == 2
            } == true
        }
        // Discard the singleton so reopening must recover the database progress.
        instrumentation.runOnMainSync {
            ReadManga.clearMangaChapter()
            ReadManga.book = null
        }
        launchReader(chapter = 1, page = 2)
        assertTrue(AppConfig.mangaRightToLeft)
        assertLayout(horizontal = true, rightToLeft = true)
    }

    @Test
    fun verticalModeKeepsForwardScrollingAndHidesDirectionOption() {
        AppConfig.mangaRightToLeft = true
        launchReader()
        toggle(R.id.menu_enable_horizontal_scroll)
        assertLayout(horizontal = false, rightToLeft = false)
        awaitPage(1, 1)
        swipe(0.5f, 0.85f, 0.5f, 0.15f)
        awaitActivity("vertical swipe advances real content") {
            val page = visiblePage(it)
            page != null && page.chapterIndex * 4 + page.index > 5 && imageLoaded(it, page)
        }
        val forwardPage = currentPage()
        key(KeyEvent.KEYCODE_PAGE_UP)
        awaitActivity("vertical previous key moves back") {
            val page = visiblePage(it)
            page != null && page.chapterIndex * 4 + page.index < forwardPage.first * 4 + forwardPage.second &&
                imageLoaded(it, page)
        }
        val anchor = currentPage()
        toggle(R.id.menu_enable_horizontal_scroll)
        awaitPage(anchor.first, anchor.second)
        assertLayout(horizontal = true, rightToLeft = true)
    }

    @Test
    fun rightToLeftPagingWorksWithSnappingAndAnimationDisabled() {
        AppConfig.mangaRightToLeft = true
        launchReader()
        toggle(R.id.menu_disable_horizontal_page_snap)
        assertTrue(AppConfig.disableHorizontalPageSnap)
        key(KeyEvent.KEYCODE_PAGE_DOWN)
        awaitPage(1, 2)
        key(KeyEvent.KEYCODE_PAGE_UP)
        awaitPage(1, 1)
        toggle(R.id.menu_disable_horizontal_page_snap)
        toggle(R.id.menu_disable_manga_page_anim)
        assertTrue(AppConfig.disableMangaPageAnim)
        assertLogicalKeys()
        toggle(R.id.menu_manga_right_to_left)
        awaitPage(1, 1)
        tap(0.84f, 0.82f)
        awaitPage(1, 2)
        toggle(R.id.menu_manga_right_to_left)
        awaitPage(1, 2)
        tap(0.84f, 0.82f)
        awaitPage(1, 1)
        assertLayout(horizontal = true, rightToLeft = true)
    }

    @Test
    fun rightToLeftTraversesDefaultChapterTitleSeparatorsInBothDirections() {
        AppConfig.hideMangaTitle = false
        AppConfig.mangaRightToLeft = true
        launchReader()
        key(KeyEvent.KEYCODE_PAGE_DOWN)
        awaitPage(1, 2)
        key(KeyEvent.KEYCODE_PAGE_DOWN)
        awaitPage(1, 3)
        key(KeyEvent.KEYCODE_PAGE_DOWN)
        awaitSeparator(1, 4)
        key(KeyEvent.KEYCODE_PAGE_DOWN)
        awaitSeparator(2, -1)
        key(KeyEvent.KEYCODE_PAGE_DOWN)
        awaitPage(2, 0)
        key(KeyEvent.KEYCODE_PAGE_UP)
        awaitSeparator(2, -1)
        key(KeyEvent.KEYCODE_PAGE_UP)
        awaitSeparator(1, 4)
        key(KeyEvent.KEYCODE_PAGE_UP)
        awaitPage(1, 3)
        assertLayout(horizontal = true, rightToLeft = true)
        screenshot("manga-direction-chapter-separators")
    }

    @Test
    fun settingsBackupRestoresDirectionAndLegacyBackupUsesLeftToRight() {
        val directory = File(context.cacheDir, "manga-direction-backup-${UUID.randomUUID()}")
        val oldIgnoreConfig = HashMap(BackupConfig.ignoreConfig)
        try {
            BackupConfig.ignoreConfig.clear()
            AppConfig.mangaRightToLeft = true
            writePreferenceSnapshot(context, directory.path, "config") {
                preferences.all.forEach { (key, value) -> putValue(key, value) }
            }
            assertEquals(true, readPreferenceSnapshot(context, directory.path, "config")
                ?.get(PreferKey.mangaRightToLeft))
            AppConfig.mangaRightToLeft = false
            BackupConfig.ignoreConfig["readConfig"] = true
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.path) }
            assertFalse(AppConfig.mangaRightToLeft)
            BackupConfig.ignoreConfig.remove("readConfig")
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.path) }
            assertTrue(AppConfig.mangaRightToLeft)
            launchReader()
            assertLayout(horizontal = true, rightToLeft = true)
            scenario!!.close()
            scenario = null
            writePreferenceSnapshot(context, directory.path, "config") {
                preferences.all.filterKeys { it != PreferKey.mangaRightToLeft }
                    .forEach { (key, value) -> putValue(key, value) }
            }
            BackupConfig.ignoreConfig["readConfig"] = true
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.path) }
            assertTrue(AppConfig.mangaRightToLeft)
            BackupConfig.ignoreConfig.remove("readConfig")
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.path) }
            assertFalse(AppConfig.mangaRightToLeft)
            launchReader()
            assertLayout(horizontal = true, rightToLeft = false)
        } finally {
            BackupConfig.ignoreConfig.clear()
            BackupConfig.ignoreConfig.putAll(oldIgnoreConfig)
            directory.deleteRecursively()
        }
    }

    @Test
    fun progressTrackAndChapterButtonsFollowTheSelectedDirection() {
        launchReader()
        listOf(false, true).forEach { rightToLeft ->
            if (AppConfig.mangaRightToLeft != rightToLeft) {
                toggle(R.id.menu_manga_right_to_left)
            }
            scenario!!.onActivity { it.ui.mangaMenu.runMenuIn(false) }
            awaitActivity("chapter buttons placed in the selected direction") { activity ->
                val previous = activity.findViewById<View>(R.id.tv_pre)
                val next = activity.findViewById<View>(R.id.tv_next)
                if (rightToLeft) next.left < previous.left else previous.left < next.left
            }
            seekAtEdge(left = true)
            awaitPage(1, if (rightToLeft) 3 else 0)
            seekAtEdge(left = false)
            awaitPage(1, if (rightToLeft) 0 else 3)
            scenario!!.onActivity { it.ui.mangaMenu.runMenuOut(false) }
        }
    }

    @Test
    fun rightToLeftBookBoundariesKeepFirstAndLastImageProgress() {
        AppConfig.mangaRightToLeft = true
        launchReader()
        moveTo(0, 0)
        key(KeyEvent.KEYCODE_PAGE_UP)
        swipe(0.85f, 0.5f, 0.15f, 0.5f)
        awaitPage(0, 0)
        scenario!!.onActivity { assertFalse(it.ui.recyclerView.canScrollHorizontally(1)) }

        moveTo(2, 3)
        key(KeyEvent.KEYCODE_PAGE_DOWN)
        var footerVisible = false
        awaitActivity("last image or existing end-of-book footer") { activity ->
            footerVisible = endFooterVisible(activity)
            val page = visiblePage(activity)
            ReadManga.durChapterIndex == 2 && ReadManga.durChapterPos == 3 &&
                (footerVisible || page?.let {
                    it.chapterIndex == 2 && it.index == 3 && imageLoaded(activity, it)
                } == true)
        }
        if (footerVisible) {
            repeat(2) {
                key(KeyEvent.KEYCODE_PAGE_DOWN)
                awaitActivity("end-of-book footer does not advance beyond the last image") {
                    endFooterVisible(it) && ReadManga.durChapterIndex == 2 &&
                        ReadManga.durChapterPos == 3 && !it.ui.recyclerView.canScrollHorizontally(-1)
                }
            }
            key(KeyEvent.KEYCODE_PAGE_UP)
        }
        awaitPage(2, 3)
        scenario!!.onActivity { ReadManga.saveRead() }
        waitUntil("last image progress persisted without an out-of-range chapter") {
            appDb.bookDao.getBook(book.bookUrl)?.let {
                it.durChapterIndex == 2 && it.durChapterPos == 3
            } == true
        }
    }

    @Test
    fun actualPortraitAndLandscapeRotationKeepImageAndReadingDirection() {
        AppConfig.mangaRightToLeft = true
        launchReader()
        key(KeyEvent.KEYCODE_PAGE_DOWN)
        awaitPage(1, 2)
        var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        scenario!!.onActivity { originalOrientation = it.requestedOrientation }
        try {
            listOf(
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to Configuration.ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to Configuration.ORIENTATION_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to Configuration.ORIENTATION_PORTRAIT,
            ).forEach { (requested, expected) ->
                scenario!!.onActivity { it.requestedOrientation = requested }
                awaitActivity("actual orientation $expected and resized reader") {
                    val recycler = it.ui.recyclerView
                    it.resources.configuration.orientation == expected &&
                        if (expected == Configuration.ORIENTATION_LANDSCAPE) {
                            recycler.width > recycler.height
                        } else {
                            recycler.height > recycler.width
                        }
                }
                awaitPage(1, 2)
                assertLayout(horizontal = true, rightToLeft = true)
                assertTrue(AppConfig.mangaRightToLeft)
                screenshot("manga-direction-rotation-$expected")
            }
        } finally {
            scenario?.onActivity { it.requestedOrientation = originalOrientation }
        }
    }

    @Test
    fun menuAutoPageAndContinuousAutoScrollAdvanceRightToLeftImages() {
        AppConfig.mangaRightToLeft = true
        AppConfig.mangaAutoPageSpeed = 1
        launchReader()
        val afterAutoPage = advanceAutomatically(R.id.menu_enable_auto_page)
        assertLayout(horizontal = true, rightToLeft = true)
        scenario!!.close()
        scenario = null
        ReadManga.executor.submit {}.get(30, TimeUnit.SECONDS)

        // The activity initializes its timer from this setting, in pixels per 16 ms for scrolling.
        AppConfig.mangaAutoPageSpeed = 16
        launchReader(afterAutoPage.first, afterAutoPage.second)
        advanceAutomatically(R.id.menu_enable_auto_scroll)
        assertLayout(horizontal = true, rightToLeft = true)
        screenshot("manga-direction-auto-scroll")
    }

    private fun moveTo(chapter: Int, page: Int) {
        scenario!!.onActivity {
            ReadManga.setProgress(BookProgress(book).copy(
                durChapterIndex = chapter, durChapterPos = page,
            ))
        }
        awaitPage(chapter, page)
    }

    private fun endFooterVisible(activity: ReadMangaActivity): Boolean {
        val recycler = activity.ui.recyclerView
        val adapter = recycler.adapter as MangaAdapter
        return (0 until recycler.childCount).any { index ->
            val child = recycler.getChildAt(index)
            recycler.getChildAdapterPosition(child) == adapter.getActualItemCount() &&
                child.left <= recycler.width / 2 && child.right > recycler.width / 2 &&
                child.findViewById<TextView>(R.id.tv_text)?.let {
                    it.visibility == View.VISIBLE && it.text.toString() == "暂无章节了！"
                } == true
        }
    }

    private fun advanceAutomatically(menuId: Int): Pair<Int, Int> {
        val start = currentPage()
        var advanced: Pair<Int, Int>? = null
        scenario!!.onActivity { setAutomaticMenu(it, menuId, true) }
        try {
            // Continuous scrolling never becomes idle; stop from the same main-thread observation.
            waitUntil("automatic menu $menuId advances to a loaded image") {
                scenario!!.onActivity { activity ->
                    val page = visiblePage(activity)
                    if (!activity.ui.recyclerView.isComputingLayout && page != null &&
                        page.chapterIndex * 4 + page.index > start.first * 4 + start.second &&
                        imageLoaded(activity, page)) {
                        advanced = page.chapterIndex to page.index
                        setAutomaticMenu(activity, menuId, false)
                    }
                }
                advanced != null
            }
        } finally {
            scenario?.onActivity { setAutomaticMenu(it, menuId, false) }
        }
        return checkNotNull(advanced).also { (chapter, page) -> awaitPage(chapter, page) }
    }

    private fun setAutomaticMenu(activity: ReadMangaActivity, id: Int, enabled: Boolean) {
        val item = activity.findViewById<TitleBar>(R.id.title_bar).menu.findItem(id)
        if (item.isChecked != enabled) {
            activity.ui.mangaMenu.runMenuIn(false)
            assertTrue(item.isVisible && item.isEnabled)
            activity.onCompatOptionsItemSelected(item)
            activity.ui.mangaMenu.runMenuOut(false)
        }
        assertEquals(enabled, item.isChecked)
    }

    private fun launchReader(chapter: Int = 1, page: Int = 1) {
        scenario = ActivityScenario.launch(Intent(context, ReadMangaActivity::class.java)
            .putExtra("bookUrl", book.bookUrl))
        awaitPage(chapter, page)
        awaitActivity("adjacent cached chapters loaded") {
            (it.ui.recyclerView.adapter as MangaAdapter).getItems()
                .filterIsInstance<MangaPage>().map { page -> page.chapterIndex }
                .containsAll((chapter - 1..chapter + 1).filter { index -> index in 0..2 })
        }
        // Android displays its first-use fullscreen confirmation after the reader has loaded.
        // Acknowledge that real system UI before injecting page keys or gestures underneath it.
        instrumentation.uiAutomation.waitForIdle(1_000, 10_000)
        instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
            val confirmation = root.findAccessibilityNodeInfosByViewId("android:id/ok")
                .firstOrNull { it.isClickable }
                ?: root.findAccessibilityNodeInfosByText("Got it").firstOrNull { it.isClickable }
            confirmation?.let {
                assertTrue(it.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }
        }
        awaitActivity("reader focused after fullscreen confirmation") { it.hasWindowFocus() }
    }

    private fun assertLogicalKeys() {
        listOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE)
            .forEach { nextKey ->
                key(nextKey)
                awaitPage(1, 2)
                key(KeyEvent.KEYCODE_VOLUME_UP)
                awaitPage(1, 1)
            }
        key(KeyEvent.KEYCODE_PAGE_DOWN)
        awaitPage(1, 2)
        key(KeyEvent.KEYCODE_PAGE_UP)
        awaitPage(1, 1)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitPage(1, 2)
        key(KeyEvent.KEYCODE_DPAD_UP)
        awaitPage(1, 1)
    }

    private fun assertLayout(horizontal: Boolean, rightToLeft: Boolean) {
        scenario!!.onActivity { activity ->
            val manager = activity.ui.recyclerView.layoutManager as LinearLayoutManager
            assertEquals(if (horizontal) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL, manager.orientation)
            assertEquals(rightToLeft, manager.reverseLayout)
            assertEquals(View.LAYOUT_DIRECTION_LTR, activity.ui.recyclerView.layoutDirection)
            assertEquals(if (rightToLeft) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR,
                (activity.findViewById<View>(R.id.seek_read_page).parent as View).layoutDirection)
            val item = activity.findViewById<TitleBar>(R.id.title_bar).menu
                .findItem(R.id.menu_manga_right_to_left)
            assertEquals(horizontal, item.isVisible)
            if (horizontal) assertTrue(item.isEnabled)
            assertEquals(AppConfig.mangaRightToLeft, item.isChecked)
            val pages = (activity.ui.recyclerView.adapter as MangaAdapter).getItems()
                .filterIsInstance<MangaPage>().map { it.chapterIndex * 4 + it.index }
            assertEquals("Adapter must retain logical chapter/page order", pages.sorted(), pages)
        }
    }

    private fun toggle(id: Int) {
        scenario!!.onActivity { activity ->
            val item = activity.findViewById<TitleBar>(R.id.title_bar).menu.findItem(id)
            assertTrue(item.isVisible && item.isEnabled)
            activity.onCompatOptionsItemSelected(item)
        }
        instrumentation.waitForIdleSync()
    }

    private fun chapterButton(id: Int) {
        scenario!!.onActivity { activity ->
            activity.ui.mangaMenu.runMenuIn(false)
            assertTrue(activity.findViewById<View>(id).performClick())
            activity.ui.mangaMenu.runMenuOut(false)
        }
    }

    private fun key(code: Int) {
        awaitActivity("reader window focused before key") { it.hasWindowFocus() }
        lastInput = KeyEvent.keyCodeToString(code)
        instrumentation.sendKeyDownUpSync(code)
    }

    private fun seekAtEdge(left: Boolean) {
        awaitActivity("reader window focused before progress input") { it.hasWindowFocus() }
        lastInput = "seek left=$left"
        val location = IntArray(2)
        var x = 0f
        var y = 0f
        scenario!!.onActivity { activity ->
            val seekBar = activity.findViewById<SeekBar>(R.id.seek_read_page)
            seekBar.getLocationOnScreen(location)
            val offset = if (left) seekBar.paddingLeft else seekBar.width - seekBar.paddingRight
            x = location[0] + offset.coerceIn(1, seekBar.width - 1).toFloat()
            y = location[1] + seekBar.height / 2f
        }
        val downTime = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                instrumentation.sendPointerSync(event)
            } finally {
                event.recycle()
            }
        }
    }

    private fun tap(x: Float, y: Float) = touch(x, y, x, y, steps = 0)

    private fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float) =
        touch(fromX, fromY, toX, toY, steps = 20)

    private fun touch(fromX: Float, fromY: Float, toX: Float, toY: Float, steps: Int) {
        awaitActivity("reader window focused before touch") { it.hasWindowFocus() }
        lastInput = "touch $fromX,$fromY to $toX,$toY"
        val location = IntArray(2)
        var width = 0
        var height = 0
        scenario!!.onActivity {
            it.ui.webtoonFrame.getLocationOnScreen(location)
            width = it.ui.webtoonFrame.width
            height = it.ui.webtoonFrame.height
        }
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, fraction: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                location[0] + width * (fromX + (toX - fromX) * fraction),
                location[1] + height * (fromY + (toY - fromY) * fraction), 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                instrumentation.sendPointerSync(event)
            } finally {
                event.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, 0f)
        repeat(steps) { step ->
            SystemClock.sleep(20)
            send(MotionEvent.ACTION_MOVE, (step + 1f) / steps)
        }
        SystemClock.sleep(20)
        send(MotionEvent.ACTION_UP, 1f)
    }

    private fun visiblePage(activity: ReadMangaActivity): MangaPage? {
        val recycler = activity.ui.recyclerView
        return (recycler.adapter as MangaAdapter).getItem(recycler.findCenterViewPosition()) as? MangaPage
    }

    private fun imageLoaded(activity: ReadMangaActivity, page: MangaPage): Boolean {
        val recycler = activity.ui.recyclerView
        val holder = recycler.findViewHolderForAdapterPosition(recycler.findCenterViewPosition())
            as? MangaAdapter.PageViewHolder ?: return false
        val bitmap = (holder.binding.image.drawable as? BitmapDrawable)?.bitmap ?: return false
        if (holder.binding.flProgress.visibility != View.GONE ||
            holder.binding.image.tag != imageUrl(page.chapterIndex, page.index)) return false
        // Glide may use a hardware bitmap; inspect a software copy without changing its request.
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return false
        val actual = try {
            copy.getPixel(copy.width / 2, copy.height / 2)
        } finally {
            copy.recycle()
        }
        val expected = pageColor(page.chapterIndex, page.index)
        return abs(Color.red(actual) - Color.red(expected)) <= 4 &&
            abs(Color.green(actual) - Color.green(expected)) <= 4 &&
            abs(Color.blue(actual) - Color.blue(expected)) <= 4
    }

    private fun currentPage(): Pair<Int, Int> {
        var page: MangaPage? = null
        scenario!!.onActivity { page = visiblePage(it) }
        return checkNotNull(page).let { it.chapterIndex to it.index }
    }

    private fun awaitPage(chapter: Int, index: Int) {
        awaitActivity("displayed chapter $chapter page $index and matching reader position") { activity ->
            val page = visiblePage(activity)
            ReadManga.book?.bookUrl == book.bookUrl &&
                ReadManga.durChapterIndex == chapter && ReadManga.durChapterPos == index &&
                page?.chapterIndex == chapter && page.index == index && imageLoaded(activity, page)
        }
    }

    private fun awaitSeparator(chapter: Int, index: Int) {
        awaitActivity("chapter $chapter separator $index") { activity ->
            val recycler = activity.ui.recyclerView
            val adapter = recycler.adapter as MangaAdapter
            (0 until recycler.childCount).any { childIndex ->
                val child = recycler.getChildAt(childIndex)
                val item = adapter.getItem(recycler.getChildAdapterPosition(child)) as? ReaderLoading
                item?.chapterIndex == chapter && item.index == index &&
                    child.left <= recycler.width / 2 && child.right > recycler.width / 2
            }
        }
    }

    private fun awaitActivity(message: String, condition: (ReadMangaActivity) -> Boolean) {
        waitUntil(message) {
            var matched = false
            scenario!!.onActivity {
                matched = !it.ui.recyclerView.isComputingLayout &&
                    it.ui.recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE && condition(it)
            }
            matched
        }
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        var state = "activity closed"
        if (scenario != null) {
            screenshot("manga-direction-failure-${testName.methodName}")
            scenario!!.onActivity { activity ->
                val recycler = activity.ui.recyclerView
                val page = visiblePage(activity)
                state = "visible=${page?.chapterIndex}/${page?.index}, " +
                    "focus=${activity.hasWindowFocus()}, menu=${activity.ui.mangaMenu.visibility}, " +
                    "loading=${activity.ui.flLoading.visibility}, " +
                    "scroll=${recycler.scrollState}, size=${recycler.width}x${recycler.height}, " +
                    "canScroll=${recycler.canScrollHorizontally(-1)}/${recycler.canScrollHorizontally(1)}, " +
                    "loaded=${listOf(ReadManga.prevMangaChapter, ReadManga.curMangaChapter,
                        ReadManga.nextMangaChapter).map { it?.chapter?.let { chapter -> "${chapter.bookUrl}/${chapter.index}" } }}, " +
                    "adapterChapters=${(recycler.adapter as MangaAdapter).getItems()
                        .filterIsInstance<MangaPage>().map { it.chapterIndex }.distinct()}, " +
                    "pendingUpdates=${recycler.hasPendingAdapterUpdates()}"
            }
        }
        assertTrue("Manga direction did not reach $message; reader is " +
            "${ReadManga.durChapterIndex}/${ReadManga.durChapterPos}; last=$lastInput; $state", condition())
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun imageUrl(chapter: Int, page: Int) = "${source.bookSourceUrl}/images/$chapter-$page.png"

    private fun pageColor(chapter: Int, page: Int) = Color.rgb(40 + chapter * 60, 50 + page * 40, 90)

    private fun SharedPreferences.Editor.putValue(key: String, value: Any?) {
        when (value) {
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is String -> putString(key, value)
            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            null -> remove(key)
        }
    }
}
