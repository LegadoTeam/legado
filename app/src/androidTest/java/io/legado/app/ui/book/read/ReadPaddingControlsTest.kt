package io.legado.app.ui.book.read

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.SeekBar
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.localBook.TextFile
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.read.config.PaddingConfigDialog
import io.legado.app.ui.widget.DetailSeekBar
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.dpToPx
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ReadPaddingControlsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun compactControlsLeaveRoomToDragAndAllRegionButtonsShareSavedMode() {
        val preferences = context.defaultSharedPreferences
        val savedMode = preferences.all[PreferKey.readPaddingLargeControls] as? Boolean
        val savedHelp = LocalConfig.all["readMenuHelpVersion"] as? Int
        val file = File.createTempFile("padding-controls-", ".txt", context.cacheDir)
        file.writeText((1..80).joinToString("\n") { "阅读边距布局验证，第 $it 行。" })
        val book = Book(bookUrl = file.absolutePath, originName = file.name, name = file.name,
            charset = "UTF-8", type = BookType.local or BookType.text, totalChapterNum = 1)
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(BookChapter(bookUrl = book.bookUrl, url = "padding-controls",
            title = "边距设置", start = 0L, end = file.length()))
        preferences.edit().remove(PreferKey.readPaddingLargeControls).commit()
        LocalConfig.edit().putInt("readMenuHelpVersion", 1).commit()
        try {
            ActivityScenario.launch<ReadBookActivity>(Intent(context, ReadBookActivity::class.java)
                .putExtra("bookUrl", book.bookUrl)).use { scenario ->
                scenario.onActivity { activity ->
                    activity.supportFragmentManager.fragments
                        .filterIsInstance<ClickActionConfigDialog>()
                        .forEach { it.view?.findViewById<View>(R.id.iv_close)?.performClick() }
                }
                scenario.onActivity { PaddingConfigDialog().showNow(it.supportFragmentManager, "padding-test") }
                awaitSize(scenario, 30)
                var compactTrackWidth = 0
                scenario.onActivity { activity ->
                    assertFalse(AppConfig.readPaddingLargeControls)
                    val root = panel(activity).requireView()
                    compactTrackWidth = verifyControls(root, 30, 26, 40)
                    assertTrue("The slider must have more width than both adjust buttons",
                        compactTrackWidth > 60.dpToPx())
                }
                val compactInterval = verifyRepeatsStop(scenario, MotionEvent.ACTION_UP)
                scenario.onActivity {
                    assertTrue(panel(it).requireView().findViewById<View>(R.id.btn_region_header).performLongClick())
                }
                awaitSize(scenario, 48)
                scenario.onActivity { activity ->
                    assertTrue(AppConfig.readPaddingLargeControls)
                    val root = panel(activity).requireView()
                    assertEquals(2 * (48.dpToPx() - 30.dpToPx()),
                        compactTrackWidth - verifyControls(root, 48, 48, 48))
                    assertTrue(root.findViewById<View>(R.id.btn_region_body).isSelected)
                }
                val largeInterval = verifyRepeatsStop(scenario, MotionEvent.ACTION_CANCEL)
                assertTrue("Large controls repeat more slowly: $compactInterval / $largeInterval ms",
                    largeInterval >= compactInterval + 25)
                File(context.getExternalFilesDir("ui-regression"), "padding-repeat-timing.txt")
                    .writeText("Median repeat intervals: compact=${compactInterval}ms large=${largeInterval}ms\n")
                verifyRepeatsStop(scenario, switchRegion = R.id.btn_region_footer)
                awaitSize(scenario, 30)
                screenshot(scenario, "padding-compact")
                scenario.onActivity { activity ->
                    val root = panel(activity).requireView()
                    verifyControls(root, 30, 26, 40)
                    assertTrue(root.findViewById<View>(R.id.btn_region_body).performLongClick())
                    panel(activity).dismissNow()
                    PaddingConfigDialog().showNow(activity.supportFragmentManager, "padding-test")
                }
                awaitSize(scenario, 48)
                screenshot(scenario, "padding-large")
                scenario.onActivity { verifyControls(panel(it).requireView(), 48, 48, 48) }
            }
        } finally {
            preferences.edit().apply {
                if (savedMode == null) remove(PreferKey.readPaddingLargeControls)
                else putBoolean(PreferKey.readPaddingLargeControls, savedMode)
            }.commit()
            LocalConfig.edit().apply {
                if (savedHelp == null) remove("readMenuHelpVersion")
                else putInt("readMenuHelpVersion", savedHelp)
            }.commit()
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookDao.delete(book)
            file.delete()
            TextFile.clear()
        }
    }

    private fun verifyControls(root: View, buttonSize: Int, iconSize: Int, tabHeight: Int): Int {
        for (id in listOf(R.id.dsb_top, R.id.dsb_bottom, R.id.dsb_left, R.id.dsb_right)) {
            val slider = root.findViewById<DetailSeekBar>(id)
            assertEquals(tabHeight.dpToPx(), slider.height)
            assertEquals(if (buttonSize == 48) 150L else 100L, slider.repeatIntervalMillis)
            for (buttonId in listOf(R.id.iv_seek_plus, R.id.iv_seek_reduce)) {
                val button = slider.findViewById<ImageView>(buttonId)
                assertEquals(buttonSize.dpToPx(), button.width)
                assertEquals(buttonSize.dpToPx(), button.height)
                assertEquals(iconSize.dpToPx().toFloat(),
                    (button.width - button.paddingLeft - button.paddingRight).toFloat(), 1f)
                assertTrue(button.isLongClickable)
            }
        }
        val header = root.findViewById<View>(R.id.btn_region_header)
        val body = root.findViewById<View>(R.id.btn_region_body)
        val footer = root.findViewById<View>(R.id.btn_region_footer)
        val reset = root.findViewById<View>(R.id.iv_reset)
        assertEquals(tabHeight.dpToPx(), header.height)
        assertEquals(header.height, reset.height)
        assertEquals(header.width.toFloat(), body.width.toFloat(), 1f)
        assertEquals(header.width.toFloat(), footer.width.toFloat(), 1f)
        assertTrue(header.right < body.left && body.right < footer.left)
        assertEquals(header.top + header.height / 2, reset.top + reset.height / 2)
        return root.findViewById<DetailSeekBar>(R.id.dsb_top).findViewById<SeekBar>(R.id.seek_bar).width
    }

    private fun verifyRepeatsStop(
        scenario: ActivityScenario<ReadBookActivity>,
        endAction: Int = MotionEvent.ACTION_CANCEL,
        switchRegion: Int? = null,
    ): Long {
        val samples = CopyOnWriteArrayList<Long>()
        val repeated = CountDownLatch(6)
        lateinit var slider: DetailSeekBar
        lateinit var button: View
        var originalCallback: ((Int) -> Unit)? = null
        var originalProgress = 0
        var downTime = 0L
        var expectedInterval = 0L
        fun dispatch(action: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                button.width / 2f, button.height / 2f, 0)
            try { assertTrue(button.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        scenario.onActivity {
            slider = panel(it).requireView().findViewById(R.id.dsb_top)
            button = slider.findViewById(R.id.iv_seek_plus)
            originalCallback = slider.onChanged
            originalProgress = slider.progress
            expectedInterval = slider.repeatIntervalMillis
            slider.onChanged = {
                samples += SystemClock.uptimeMillis()
                repeated.countDown()
            }
            downTime = SystemClock.uptimeMillis()
            dispatch(MotionEvent.ACTION_DOWN)
        }
        try {
            assertTrue("Holding the actual button must repeat", repeated.await(5, TimeUnit.SECONDS))
            var stoppedCount = 0
            scenario.onActivity {
                if (switchRegion == null) dispatch(endAction)
                else assertTrue(panel(it).requireView().findViewById<View>(switchRegion).performLongClick())
                stoppedCount = samples.size
            }
            SystemClock.sleep(350)
            scenario.onActivity { assertEquals("Release, cancel and mode changes must stop repeats", stoppedCount, samples.size) }
            assertTrue("The initial long press delay must be preserved",
                samples.first() - downTime >= ViewConfiguration.getLongPressTimeout() + 90L)
            val gaps = samples.zipWithNext { first, second -> second - first }
            assertTrue("Repeats must respect the configured interval: $gaps", gaps.all { it >= expectedInterval - 5 })
            return gaps.sorted()[gaps.size / 2]
        } finally {
            scenario.onActivity {
                dispatch(MotionEvent.ACTION_CANCEL)
                slider.onChanged = originalCallback
                slider.progress = originalProgress
            }
        }
    }

    private fun panel(activity: ReadBookActivity) = activity.supportFragmentManager
        .findFragmentByTag("padding-test") as PaddingConfigDialog

    private fun awaitSize(scenario: ActivityScenario<ReadBookActivity>, size: Int) {
        val deadline = SystemClock.uptimeMillis() + 10000
        do {
            var ready = false
            scenario.onActivity {
                val slider = panel(it).view?.findViewById<View>(R.id.dsb_top)
                ready = slider?.findViewById<View>(R.id.iv_seek_plus)?.width == size.dpToPx()
            }
            if (ready) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("Padding controls did not lay out at ${size}dp")
    }

    private fun screenshot(scenario: ActivityScenario<ReadBookActivity>, name: String) {
        instrumentation.waitForIdleSync()
        val rendered = CountDownLatch(1)
        scenario.onActivity {
            val decor = checkNotNull(panel(it).requireDialog().window).decorView
            decor.postOnAnimation { decor.postOnAnimation { rendered.countDown() } }
        }
        assertTrue("The padding dialog must draw before capture", rendered.await(5, TimeUnit.SECONDS))
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
