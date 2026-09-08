package io.legado.app.ui.book.read

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.preference.SeekBarPreference
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
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.read.config.ReadAloudControlsDialog
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.utils.defaultSharedPreferences
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual reader layout and touch bounds; this fixture does not test service lifecycle. */
@RunWith(AndroidJUnit4::class)
class ReadAloudScaleUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val savedMenuHelp = LocalConfig.all["readMenuHelpVersion"]
    private val savedPrefs = listOf(PreferKey.readAloudControlsPause, PreferKey.readAloudControlsSize,
        PreferKey.readAloudControlsDrag, PreferKey.readAloudControlsDock, "readAloudControlsWidth")
        .associateWith { prefs.all[it] }
    private val savedRunning = BaseReadAloudService.isRun
    private val savedPaused = BaseReadAloudService.pause
    private val savedFollowing = BaseReadAloudService.followReadAloudPosition
    private var scenario: ActivityScenario<ReadBookActivity>? = null
    private var book: Book? = null
    private var textFile: File? = null

    @Before fun setUp() {
        prefs.edit().putBoolean(PreferKey.readAloudControlsPause, true)
            .putBoolean(PreferKey.readAloudControlsDrag, false)
            .putBoolean(PreferKey.readAloudControlsDock, false)
            .remove("readAloudControlsWidth").commit()
        LocalConfig.edit().putInt("readMenuHelpVersion", 1).commit()
        val file = File.createTempFile("aloud-menu-", ".txt", context.cacheDir).also { textFile = it }
        file.writeText((0..60).joinToString("\n") { "Reader content line $it for the playback menu regression." })
        val fixture = Book(bookUrl = file.absolutePath, originName = file.name, name = file.name,
            charset = "UTF-8", type = BookType.local or BookType.text, totalChapterNum = 1)
            .apply { setPageAnim(PageAnim.noAnim) }
        book = fixture
        appDb.bookDao.insert(fixture)
        appDb.bookChapterDao.insert(BookChapter(bookUrl = fixture.bookUrl, url = "aloud-menu-chapter",
            title = "Playback controls", start = 0L, end = file.length()))
        scenario = ActivityScenario.launch(Intent(context, ReadBookActivity::class.java)
            .putExtra("bookUrl", fixture.bookUrl))
        scenario!!.onActivity { activity ->
            activity.supportFragmentManager.fragments.filterIsInstance<ClickActionConfigDialog>()
                .forEach { it.view?.findViewById<View>(R.id.iv_close)?.performClick() }
        }
        await("reader content") {
            ReadBook.book?.bookUrl == fixture.bookUrl && ReadBook.curTextChapter?.isCompleted == true &&
                !it.findViewById<ReadView>(R.id.read_view).curPage.textPage.isMsgPage && it.bottomDialog == 0
        }
    }

    @After fun tearDown() {
        instrumentation.runOnMainSync {
            playbackFlag("isRun", savedRunning)
            playbackFlag("pause", savedPaused)
            if (savedFollowing) BaseReadAloudService.restoreReadAloudFollow()
            else BaseReadAloudService.detachReadAloudFollow()
        }
        scenario?.close()
        book?.let {
            appDb.bookChapterDao.delByBook(it.bookUrl)
            appDb.bookDao.delete(it)
        }
        textFile?.delete()
        TextFile.clear()
        LocalConfig.edit().apply {
            if (savedMenuHelp == null) remove("readMenuHelpVersion")
            else putInt("readMenuHelpVersion", savedMenuHelp as Int)
        }.commit()
        prefs.edit().apply {
            savedPrefs.forEach { (key, value) ->
                when (value) {
                    null -> remove(key)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                }
            }
        }.commit()
    }

    @Test fun widthControlsWholeBarAndCircleWithMatchingTouchBounds() {
        val evidence = StringBuilder()
        for (widthDp in listOf(288, 144, 40, 432)) {
            prefs.edit().putInt("readAloudControlsWidth", widthDp).commit()
            scenario!!.onActivity {
                playbackFlag("isRun", true)
                BaseReadAloudService.detachReadAloudFollow()
                it.showReadAloudControls()
            }
            await("position bar visible") { it.findViewById<View>(R.id.ll_back_to_speech).isShown }
            screenshot("aloud-scale-position-$widthDp")
            var expectedHeight = 0
            val bounds = Rect()
            val clicks = IntArray(2)
            scenario!!.onActivity { activity ->
                val bar = activity.findViewById<View>(R.id.read_aloud_float_bar_container)
                val density = activity.resources.displayMetrics.density
                val parent = bar.parent as View
                val expectedWidth = minOf((widthDp * density).roundToInt(), parent.width - (32 * density).roundToInt())
                expectedHeight = (expectedWidth / 6f).roundToInt()
                evidence.appendLine("requested=$widthDp actual=${bar.width}x${bar.height} expected=${expectedWidth}x$expectedHeight")
                File(context.getExternalFilesDir("ui-regression"), "aloud-scale-bounds.txt").writeText(evidence.toString())
                assertEquals("Long control must use the selected total width", expectedWidth, bar.width)
                assertEquals("Long control height must scale with its width", expectedHeight, bar.height)
                assertEquals(1f, bar.scaleX, 0f)
                assertEquals(1f, bar.scaleY, 0f)
                assertTrue(bar.getGlobalVisibleRect(bounds))
                assertEquals(bar.width, bounds.width())
                assertEquals(bar.height, bounds.height())
                listOf(R.id.ll_back_to_speech, R.id.ll_read_from_here).forEachIndexed { index, id ->
                    activity.findViewById<View>(id).setOnClickListener { clicks[index]++ }
                }
                for (id in listOf(R.id.tv_back_to_speech, R.id.tv_read_from_here)) {
                    val text = activity.findViewById<TextView>(id)
                    val layout = checkNotNull(text.layout)
                    assertTrue("Text must fit vertically", layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                    for (line in 0 until layout.lineCount) {
                        assertEquals("Action text must remain complete", 0, layout.getEllipsisCount(line))
                        assertTrue("Action text must fit horizontally",
                            layout.getLineWidth(line) <= text.width - text.compoundPaddingLeft - text.compoundPaddingRight + 1)
                    }
                }
            }
            tap(bounds.left + bounds.width() * .25f, bounds.exactCenterY())
            tap(bounds.left + bounds.width() * .75f, bounds.exactCenterY())
            tap(bounds.exactCenterX(), bounds.top - 3f)
            scenario!!.onActivity {
                assertEquals("Only the first visible action should receive its tap", 1, clicks[0])
                assertEquals("Only the second visible action should receive its tap", 1, clicks[1])
                it.findViewById<ReadMenu>(R.id.read_menu).runMenuOut(anim = false)
            }
            for (paused in listOf(false, true)) {
                scenario!!.onActivity {
                    playbackFlag("pause", paused)
                    BaseReadAloudService.restoreReadAloudFollow()
                    it.showReadAloudControls()
                }
                await("pause control visible") { it.findViewById<View>(R.id.iv_pause_aloud).isShown }
                screenshot("aloud-scale-circle-$widthDp-$paused")
                scenario!!.onActivity { activity ->
                    val bar = activity.findViewById<View>(R.id.read_aloud_float_bar_container)
                    val pause = activity.findViewById<View>(R.id.iv_pause_aloud)
                    assertEquals("Circle follows the same scale", expectedHeight, bar.width)
                    assertEquals(expectedHeight, bar.height)
                    assertEquals(expectedHeight, pause.width)
                    assertEquals(expectedHeight, pause.height)
                }
            }
        }
        File(context.getExternalFilesDir("ui-regression"), "aloud-scale-bounds.txt").writeText(evidence.toString())
    }

    @Test fun legacySizeAndSelectedWidthSurviveSettingsAndReaderRecreation() {
        prefs.edit().putInt(PreferKey.readAloudControlsSize, 72).remove("readAloudControlsWidth").commit()
        scenario!!.onActivity {
            ReadAloudControlsDialog().show(it.supportFragmentManager, "scale-settings")
        }
        await("width preference") { activity ->
            val dialog = activity.supportFragmentManager.findFragmentByTag("scale-settings")
            val fragment = dialog?.childFragmentManager?.findFragmentByTag("controls")
                as? ReadAloudControlsDialog.ControlsPreferenceFragment
            fragment?.findPreference<SeekBarPreference>("readAloudControlsWidth") != null
        }
        screenshot("aloud-scale-legacy-settings")
        scenario!!.onActivity { activity ->
            val dialog = activity.supportFragmentManager.findFragmentByTag("scale-settings") as ReadAloudControlsDialog
            val fragment = dialog.childFragmentManager.findFragmentByTag("controls")
                as ReadAloudControlsDialog.ControlsPreferenceFragment
            val width = checkNotNull(fragment.findPreference<SeekBarPreference>("readAloudControlsWidth"))
            assertEquals("Old 72dp height maps to the original long width", 432, width.value)
            assertEquals(40, width.min)
            assertEquals(432, width.max)
            width.value = 40
            dialog.dismiss()
        }
        await("settings dismissed") { it.bottomDialog == 0 }
        scenario!!.recreate()
        scenario!!.onActivity {
            playbackFlag("isRun", true)
            BaseReadAloudService.detachReadAloudFollow()
            it.showReadAloudControls()
        }
        await("restored position control") { it.findViewById<View>(R.id.ll_back_to_speech).isShown }
        screenshot("aloud-scale-restored-40")
        scenario!!.onActivity { activity ->
            assertEquals(40, prefs.getInt("readAloudControlsWidth", -1))
            val bar = activity.findViewById<View>(R.id.read_aloud_float_bar_container)
            assertEquals((40 * activity.resources.displayMetrics.density).roundToInt(), bar.width)
        }
    }

    private fun tap(x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0).let {
                try { instrumentation.sendPointerSync(it) } finally { it.recycle() }
            }
        }
        instrumentation.waitForIdleSync()
    }

    private fun playbackFlag(name: String, value: Boolean) {
        BaseReadAloudService::class.java.getDeclaredField(name).apply { isAccessible = true }
            .setBoolean(null, value)
    }

    private fun await(description: String, condition: (ReadBookActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30000
        do {
            var ready = false
            scenario!!.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Timed out waiting for $description")
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val rendered = CountDownLatch(1)
        scenario!!.onActivity {
            val decor = it.window.decorView
            decor.postOnAnimation { decor.postOnAnimation { rendered.countDown() } }
        }
        assertTrue(rendered.await(5, TimeUnit.SECONDS))
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}
