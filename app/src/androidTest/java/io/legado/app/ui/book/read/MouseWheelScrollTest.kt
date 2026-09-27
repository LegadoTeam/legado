package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.read.config.MoreConfigDialog
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.utils.defaultSharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MouseWheelScrollTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = context.defaultSharedPreferences
    private val savedPreferences = listOf(PreferKey.mouseWheelPage, PreferKey.mouseWheelScrollSpeed)
        .associateWith { preferences.all[it] }
    private lateinit var file: File
    private lateinit var book: Book
    private var scenario: ActivityScenario<ReadBookActivity>? = null
    // This is the offset used by ContentTextView.drawPage, not a duplicate speed calculation.
    private val pageOffset = ContentTextView::class.java.getDeclaredField("pageOffset")
        .apply { isAccessible = true }

    @Before
    fun setUp() {
        assertTrue(preferences.edit().putBoolean(PreferKey.mouseWheelPage, true)
            .remove(PreferKey.mouseWheelScrollSpeed).commit())
        file = File.createTempFile("mouse-wheel-", ".txt", context.cacheDir)
        file.writeText((0 until 300).joinToString("\n") {
            "Line $it: deterministic local reader text for mouse wheel scrolling."
        })
        book = Book(bookUrl = file.absolutePath, originName = file.name, name = file.name,
            charset = "UTF-8", type = BookType.local or BookType.text, totalChapterNum = 1)
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(BookChapter(bookUrl = book.bookUrl, url = "wheel-chapter",
            title = "Mouse wheel fixture", start = 0L, end = file.length()))
    }

    @After
    fun tearDown() {
        scenario?.close()
        TextFile.clear()
        if (::book.isInitialized) appDb.bookDao.delete(book)
        if (::file.isInitialized) file.delete()
        val editor = preferences.edit()
        savedPreferences.forEach { (key, value) ->
            when (value) {
                null -> editor.remove(key)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
            }
        }
        assertTrue(editor.commit())
    }

    @Test
    fun defaultHalfAndDoubleSpeedMoveRealContentAndSwitchStopsMovement() {
        launchReader(PageAnim.scrollPageAnim)
        assertEquals(100, AppConfig.mouseWheelScrollSpeed)
        assertScrollDelta(-50) { dispatchScroll(it, -1f) }
        setSpeed(50)
        assertScrollDelta(-25) { dispatchScroll(it, -1f) }
        setSpeed(200)
        assertScrollDelta(-100) { dispatchScroll(it, -1f) }
        assertScrollDelta(100) { dispatchScroll(it, 1f) }
        assertTrue(preferences.edit().putBoolean(PreferKey.mouseWheelPage, false).commit())
        assertScrollDelta(0) { dispatchScroll(it, -1f) }
        assertTrue(preferences.edit().putBoolean(PreferKey.mouseWheelPage, true).commit())
        assertScrollDelta(-100) { dispatchScroll(it, -1f) }
        setSpeed(1)
        assertScrollDelta(-5) { dispatchScroll(it, -1f) }
        setSpeed(500)
        assertScrollDelta(-200) { dispatchScroll(it, -1f) }
    }

    @Test
    fun rotaryUsesScrollAxisAndInvalidOrHorizontalEventsDoNotMoveContent() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        launchReader(PageAnim.scrollPageAnim)
        assertScrollDelta(-50) { dispatchScroll(it, -1f) }
        setSpeed(200)
        assertScrollDelta(-100) {
            dispatchScroll(it, -1f, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL)
        }
        assertScrollDelta(0) { activity ->
            dispatchScroll(activity, 0f)
            dispatchScroll(activity, -1f, axis = MotionEvent.AXIS_HSCROLL)
            dispatchScroll(activity, Float.NaN)
            dispatchScroll(activity, Float.POSITIVE_INFINITY)
            dispatchScroll(activity, -1f, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_VSCROLL)
        }
        assertTrue(preferences.edit().putBoolean(PreferKey.mouseWheelPage, false).commit())
        assertScrollDelta(0) {
            dispatchScroll(it, -1f, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL)
        }
    }

    @Test
    fun pagedModeKeepsOnePagePerDebouncedBurstAtDifferentSpeeds() {
        launchReader(PageAnim.noAnim)
        listOf(50, 100, 200, 400).forEach { speed ->
            setSpeed(speed)
            val before = currentPage()
            scenario!!.onActivity { activity ->
                repeat(3) { dispatchScroll(activity, -1f) }
                assertEquals("Wheel paging remains delayed", before,
                    activity.readerView.curPage.textPage.index)
            }
            awaitReader { it.readerView.curPage.textPage.index == before + 1 }
            SystemClock.sleep(350)
            assertEquals("Speed $speed must not multiply page turns", before + 1, currentPage())
        }
        val before = currentPage()
        scenario!!.onActivity {
            dispatchScroll(it, 0f)
            dispatchScroll(it, -1f, axis = MotionEvent.AXIS_HSCROLL)
            dispatchScroll(it, Float.NaN)
        }
        SystemClock.sleep(350)
        assertEquals("Ignored axes must not accidentally turn backwards", before, currentPage())
        scenario!!.onActivity { dispatchScroll(it, 1f) }
        awaitReader { it.readerView.curPage.textPage.index == before - 1 }
    }

    @Test
    fun nativeSpeedControlPersistsAcrossRecreationAndFollowsTheWheelSwitch() {
        launchReader(PageAnim.scrollPageAnim)
        scenario!!.onActivity {
            MoreConfigDialog().showNow(it.supportFragmentManager, "mouse-wheel-settings")
        }
        awaitReader { preferenceFragment(it) != null }
        scenario!!.onActivity {
            preferenceFragment(it)!!.scrollToPreference(PreferKey.mouseWheelScrollSpeed)
        }
        awaitReader { speedSeekBar(it)?.isShown == true }
        scenario!!.onActivity { activity ->
            val fragment = preferenceFragment(activity)!!
            val speed = fragment.findPreference<SeekBarPreference>(PreferKey.mouseWheelScrollSpeed)!!
            assertEquals(10, speed.min)
            assertEquals(400, speed.max)
            assertEquals(100, speed.value)
            val seekBar = speedSeekBar(activity)!!
            repeat(10) {
                assertTrue(seekBar.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
            }
            assertEquals(200, speed.value)
            assertEquals(200, AppConfig.mouseWheelScrollSpeed)
        }
        screenshot("mouse-wheel-speed-settings")
        scenario!!.recreate()
        awaitReader { preferenceFragment(it) != null }
        scenario!!.onActivity {
            preferenceFragment(it)!!.scrollToPreference(PreferKey.mouseWheelScrollSpeed)
        }
        awaitReader { speedSeekBar(it)?.isShown == true }
        scenario!!.onActivity { activity ->
            val fragment = preferenceFragment(activity)!!
            val speed = fragment.findPreference<SeekBarPreference>(PreferKey.mouseWheelScrollSpeed)!!
            assertEquals(200, speed.value)
            assertEquals(200, AppConfig.mouseWheelScrollSpeed)
            val enabled = fragment.findPreference<SwitchPreferenceCompat>(PreferKey.mouseWheelPage)!!
            enabled.isChecked = false
            assertFalse(speed.isEnabled)
            assertFalse(AppConfig.mouseWheelPage)
            enabled.isChecked = true
            assertTrue(speed.isEnabled)
            (activity.supportFragmentManager.findFragmentByTag("mouse-wheel-settings")
                as MoreConfigDialog).dismissNow()
        }
        awaitReader { it.bottomDialog == 0 && !it.readerView.curPage.textPage.isMsgPage }
        assertScrollDelta(-100) { dispatchScroll(it, -1f) }
    }

    private fun launchReader(pageAnim: Int) {
        book.setPageAnim(pageAnim)
        appDb.bookDao.update(book)
        scenario = ActivityScenario.launch(Intent(context, ReadBookActivity::class.java)
            .putExtra("bookUrl", book.bookUrl))
        scenario!!.onActivity { activity ->
            activity.supportFragmentManager.fragments.filterIsInstance<ClickActionConfigDialog>()
                .forEach { it.view?.findViewById<View>(R.id.iv_close)?.performClick() }
        }
        awaitReader {
            val page = it.readerView.curPage.textPage
            ReadBook.book?.bookUrl == book.bookUrl && ReadBook.curTextChapter?.isCompleted == true &&
                !page.isMsgPage && page.lineSize > 0 && page.height > 400 && it.bottomDialog == 0
        }
        scenario!!.onActivity { assertEquals(pageAnim == PageAnim.scrollPageAnim, it.isScroll) }
    }

    private val ReadBookActivity.readerView: ReadView
        get() = findViewById(R.id.read_view)

    private fun setSpeed(speed: Int) {
        assertTrue(preferences.edit().putInt(PreferKey.mouseWheelScrollSpeed, speed).commit())
    }

    private fun assertScrollDelta(expected: Int, input: (ReadBookActivity) -> Unit) {
        scenario!!.onActivity { activity ->
            val page = activity.readerView.curPage
            val text = page.findViewById<ContentTextView>(R.id.content_text_view)
            val beforeOffset = pageOffset.getInt(text)
            val beforePage = page.textPage.index
            input(activity)
            assertEquals("Rendered content displacement", expected, pageOffset.getInt(text) - beforeOffset)
            assertEquals("Small wheel movements stay within the current page", beforePage, page.textPage.index)
        }
    }

    private fun dispatchScroll(activity: ReadBookActivity, value: Float,
        source: Int = InputDevice.SOURCE_MOUSE, axis: Int = MotionEvent.AXIS_VSCROLL) {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            x = activity.readerView.width / 2f
            y = activity.readerView.height / 2f
            setAxisValue(axis, value)
        }
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1,
            arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, source, 0)
        try { activity.dispatchGenericMotionEvent(event) }
        finally { event.recycle() }
    }

    private fun currentPage(): Int {
        var index = -1
        scenario!!.onActivity { index = it.readerView.curPage.textPage.index }
        return index
    }

    private fun preferenceFragment(activity: ReadBookActivity): MoreConfigDialog.ReadPreferenceFragment? =
        (activity.supportFragmentManager.findFragmentByTag("mouse-wheel-settings") as? MoreConfigDialog)
            ?.childFragmentManager?.findFragmentByTag("readPreferenceFragment")
            as? MoreConfigDialog.ReadPreferenceFragment

    @SuppressLint("RestrictedApi")
    private fun speedSeekBar(activity: ReadBookActivity): SeekBar? {
        val fragment = preferenceFragment(activity) ?: return null
        val recycler = fragment.listView
        val adapter = recycler.adapter as? PreferenceGroupAdapter ?: return null
        val position = adapter.getPreferenceAdapterPosition(PreferKey.mouseWheelScrollSpeed)
        return recycler.findViewHolderForAdapterPosition(position)?.itemView
            ?.findViewById(androidx.preference.R.id.seekbar)
    }

    private fun awaitReader(condition: (ReadBookActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        do {
            var ready = false
            scenario!!.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Reader did not reach the expected wheel test state")
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
