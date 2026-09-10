package io.legado.app.ui.book.read

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.HighlightRule
import io.legado.app.help.HighlightStyle
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.BackupConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.association.OpenUrlConfirmActivity
import io.legado.app.ui.book.read.config.MoreConfigDialog
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.ui.book.read.page.entities.column.TextHtmlColumn
import io.legado.app.ui.highlight.HighlightRuleActivity
import io.legado.app.utils.defaultSharedPreferences
import org.hamcrest.Matcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HighlightTriggerUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val clickKeys = listOf(PreferKey.clickActionTL, PreferKey.clickActionTC, PreferKey.clickActionTR,
        PreferKey.clickActionML, PreferKey.clickActionMC, PreferKey.clickActionMR,
        PreferKey.clickActionBL, PreferKey.clickActionBC, PreferKey.clickActionBR)
    private val savedPrefs = (clickKeys + listOf(PreferKey.highlightActionTrigger, PreferKey.clickImgWay,
        PreferKey.textSelectAble, PreferKey.adaptSpecialStyle, PreferKey.preDownloadNum))
        .associateWith { prefs.all[it] }
    private val savedHelp = listOf("readHelpVersion", "readMenuHelpVersion").associateWith { LocalConfig.all[it] }
    private val savedRules = appDb.highlightRuleDao.all
    private val id = UUID.randomUUID().toString()
    private val source = BookSource(bookSourceUrl = "https://example.invalid/highlight-source/$id")
    private val book = Book(bookUrl = "https://example.invalid/highlight/$id",
        tocUrl = "https://example.invalid/highlight/$id/toc", origin = source.bookSourceUrl,
        name = "Highlight trigger fixture", type = BookType.text, totalChapterNum = 1, canUpdate = false)
        .apply { setPageAnim(PageAnim.noAnim); setUseReplaceRule(false); setImageStyle(Book.imgStyleDefault) }
    private val chapter = BookChapter(bookUrl = book.bookUrl, url = "${book.bookUrl}/0", title = "Triggers")
    private val image = "https://example.invalid/$id/image.png," +
        """{"style":"text","click":"book.putVariable('imageDoubleTap', 'done')"}"""
    private var scenario: ActivityScenario<ReadBookActivity>? = null

    @Before fun setUp() {
        prefs.edit().putBoolean(PreferKey.textSelectAble, true).putBoolean(PreferKey.adaptSpecialStyle, true)
            .putInt(PreferKey.preDownloadNum, 0).putString(PreferKey.highlightActionTrigger, "doubleTap")
            .apply { clickKeys.forEach { putInt(it, 1) } }.commit()
        AppConfig.adaptSpecialStyle = true
        LocalConfig.edit().putInt("readHelpVersion", 1).putInt("readMenuHelpVersion", 1).commit()
        appDb.highlightRuleDao.deleteAll()
        appDb.highlightRuleDao.insert(*listOf("ALPHA", "OMEGA", "自然高亮", "HTML", "LINK").map {
            HighlightRule(name = it, pattern = it).apply { applyStyle(HighlightStyle(fill = Color.YELLOW)) }
        }.toTypedArray())
        appDb.bookSourceDao.insert(source)
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(chapter)
        BookHelp.saveText(book, chapter, "ALPHA ALPHA ordinary OMEGA\n自然高亮\n" +
            "<usehtml><b>HTML</b> <a href='https://example.invalid/highlight-link'>LINK</a></usehtml>\n" +
            "<img src=\"$image\">\n" + (0 until 80).joinToString("\n") { "Ordinary reading line $it." })
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.BLUE)
            BookHelp.writeImage(book, image, ByteArrayOutputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray()
            })
        } finally { bitmap.recycle() }
        scenario = ActivityScenario.launch(Intent(context, ReadBookActivity::class.java)
            .putExtra("bookUrl", book.bookUrl).putExtra("inBookshelf", true))
        awaitReader { view -> view.curPage.textPage.lines.any { line ->
            line.columns.any { it is TextBaseColumn && it.highlightStyle != null }
        } && ReadBook.curTextChapter?.isCompleted == true }
    }

    @After fun tearDown() {
        scenario?.close()
        BookHelp.clearCache(book)
        appDb.bookDao.delete(book)
        appDb.bookSourceDao.delete(source)
        appDb.highlightRuleDao.deleteAll()
        if (savedRules.isNotEmpty()) appDb.highlightRuleDao.insert(*savedRules.toTypedArray())
        prefs.edit().apply {
            savedPrefs.forEach { (key, value) -> when (value) {
                null -> remove(key)
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
            } }
        }.commit()
        AppConfig.adaptSpecialStyle = prefs.getBoolean(PreferKey.adaptSpecialStyle, false)
        LocalConfig.edit().apply {
            savedHelp.forEach { (key, value) -> if (value == null) remove(key) else putInt(key, value as Int) }
        }.commit()
    }

    @Test fun doubleTapNeedsTheSameTargetAndOpensTheExistingRuleManager() {
        // Use the actual preference dialog and persisted key, rather than a test-only trigger.
        scenario!!.onActivity { MoreConfigDialog().showNow(it.supportFragmentManager, "highlight-settings") }
        instrumentation.waitForIdleSync()
        scenario!!.onActivity {
            val dialog = it.supportFragmentManager.findFragmentByTag("highlight-settings") as MoreConfigDialog
            (dialog.childFragmentManager.fragments.single() as MoreConfigDialog.ReadPreferenceFragment)
                .scrollToPreference(PreferKey.highlightActionTrigger)
        }
        onView(withText(R.string.highlight_action_trigger)).inRoot(isDialog()).perform(click())
        onView(withText(R.string.highlight_action_trigger_double_tap)).inRoot(isDialog()).perform(click())
        assertEquals("doubleTap", AppConfig.highlightActionTrigger)
        assertTrue("The existing preference remains included in backup", BackupConfig.keyIsNotIgnore(PreferKey.highlightActionTrigger))
        screenshot("highlight-trigger-setting")
        pressBack()
        val alpha = point(occurrence = 1) { it is TextBaseColumn && it.charData == "A" && it.highlightStyle != null }
        val omega = point { it is TextBaseColumn && it.charData == "G" && it.highlightStyle != null }
        val otherAlpha = point(occurrence = 2) { it is TextBaseColumn && it.charData == "A" && it.highlightStyle != null }
        val dx = alpha[0] - otherAlpha[0]
        val dy = alpha[1] - otherAlpha[1]
        val slop = ViewConfiguration.get(context).scaledDoubleTapSlop
        assertTrue("Different occurrences must be close enough to test target identity",
            dx * dx + dy * dy <= slop * slop)
        val page = ReadBook.durPageIndex
        taps(alpha)
        assertEquals("First tap on a highlight must not turn the page", page, ReadBook.durPageIndex)
        noRulePopup()
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
        taps(alpha, omega)
        noRulePopup()
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
        taps(alpha, otherAlpha)
        noRulePopup()
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
        taps(alpha)
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
        taps(alpha)
        noRulePopup()
        // A non-highlight tap between two otherwise matching taps must break the pair.
        prefs.edit().apply { clickKeys.forEach { putInt(it, -1) } }.commit()
        val ordinary = point { it is TextBaseColumn && it.charData == "o" && it.highlightStyle == null }
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
        taps(alpha, ordinary, alpha)
        noRulePopup()
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
        gesture(listOf(alpha, alpha), 30, cancelBetween = true)
        noRulePopup()
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
        val firstCharacter = point { it is TextBaseColumn && it.charData == "然" }
        val nextCharacter = point { it is TextBaseColumn && it.charData == "高" }
        taps(firstCharacter, nextCharacter)
        onView(withText(R.string.highlight_rule_disable)).inRoot(isPlatformPopup()).check(matches(isDisplayed()))
        pressBack()
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
        taps(alpha, alpha)
        onView(withText(R.string.highlight_rule_disable)).inRoot(isPlatformPopup()).check(matches(isDisplayed()))
        var editY = 0
        var manageY = 0
        var disableY = 0
        onView(withText(R.string.edit)).inRoot(isPlatformPopup()).check { view, _ -> editY = screenY(view) }
        onView(withText(R.string.highlight_rule)).inRoot(isPlatformPopup()).check { view, _ -> manageY = screenY(view) }
        onView(withText(R.string.highlight_rule_disable)).inRoot(isPlatformPopup()).check { view, _ -> disableY = screenY(view) }
        assertTrue("Manage rules belongs between edit and disable", editY < manageY && manageY < disableY)
        screenshot("highlight-trigger-rule-popup")
        val monitor = instrumentation.addMonitor(HighlightRuleActivity::class.java.name, null, false)
        try {
            onView(withText(R.string.highlight_rule)).inRoot(isPlatformPopup()).perform(click())
            val manager = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
            assertNotNull("The existing rule manager must actually open", manager)
            screenshot("highlight-trigger-rule-manager")
            instrumentation.runOnMainSync { manager.finish() }
        } finally { instrumentation.removeMonitor(monitor) }
        scenario!!.recreate()
        awaitReader { !it.curPage.textPage.isMsgPage }
        assertEquals("doubleTap", AppConfig.highlightActionTrigger)
    }

    @Test fun longPressSelectionLinksImagesAndExistingModesKeepTheirBehavior() {
        val alpha = point { it is TextBaseColumn && it.charData == "P" && it.highlightStyle != null }
        hold(alpha)
        scenario!!.onActivity {
            val view = it.findViewById<ReadView>(R.id.read_view)
            assertTrue("Double tap mode must leave long press available for selection", view.isTextSelected)
            assertTrue(view.getSelectText().contains("ALPHA"))
        }
        noRulePopup()
        screenshot("highlight-trigger-selected-text")
        clearSelection()
        val html = point { it is TextHtmlColumn && it.charData.contains("H") && it.linkUrl == null }
        taps(html, html)
        onView(withText(R.string.highlight_rule_disable)).inRoot(isPlatformPopup()).check(matches(isDisplayed()))
        pressBack()
        val link = point { it is TextHtmlColumn && it.linkUrl != null }
        hold(link)
        scenario!!.onActivity { assertTrue(it.findViewById<ReadView>(R.id.read_view).isTextSelected) }
        noRulePopup()
        clearSelection()
        val monitor = instrumentation.addMonitor(OpenUrlConfirmActivity::class.java.name, null, true)
        try {
            taps(link)
            assertEquals("HTML links keep single-tap priority", 1, monitor.hits)
        } finally { instrumentation.removeMonitor(monitor) }
        prefs.edit().putString(PreferKey.clickImgWay, "4").commit()
        val imagePoint = point {
            if (it !is ImageColumn) false else {
                assertEquals("The real image column must retain the source click script",
                    "book.putVariable('imageDoubleTap', 'done')", it.click)
                true
            }
        }
        SystemClock.sleep(350)
        taps(imagePoint)
        assertEquals("", ReadBook.book!!.getVariable("imageDoubleTap"))
        SystemClock.sleep(350)
        taps(imagePoint, imagePoint)
        awaitReader { ReadBook.book?.getVariable("imageDoubleTap") == "done" }
        prefs.edit().putString(PreferKey.highlightActionTrigger, "click").commit()
        taps(alpha)
        onView(withText(R.string.highlight_rule_disable)).inRoot(isPlatformPopup()).check(matches(isDisplayed()))
        pressBack()
        prefs.edit().putString(PreferKey.highlightActionTrigger, "longPress")
            .apply { clickKeys.forEach { putInt(it, -1) } }.commit()
        taps(alpha)
        noRulePopup()
        hold(alpha)
        onView(withText(R.string.highlight_rule_disable)).inRoot(isPlatformPopup()).check(matches(isDisplayed()))
        pressBack()
        prefs.edit().putString(PreferKey.highlightActionTrigger, "off").commit()
        taps(alpha, alpha)
        noRulePopup()
        hold(alpha)
        scenario!!.onActivity { assertTrue(it.findViewById<ReadView>(R.id.read_view).isTextSelected) }
        noRulePopup()
    }

    private fun point(occurrence: Int = 0, matches: (BaseColumn) -> Boolean): FloatArray {
        var point: FloatArray? = null
        scenario!!.onActivity { activity ->
            val page = activity.findViewById<ReadView>(R.id.read_view).curPage
            var remaining = occurrence
            for (line in page.textPage.lines) {
                for (column in line.columns) {
                    if (!matches(column) || remaining-- > 0) continue
                    point = floatArrayOf((column.start + column.end) / 2 + page.imgBgPaddingStart,
                        (line.lineTop + line.lineBottom) / 2 + page.headerHeight)
                    return@onActivity
                }
            }
        }
        return checkNotNull(point) { "The real reader must contain the expected clickable column" }
    }

    private fun taps(vararg points: FloatArray) = gesture(points.toList(), 30)
    private fun hold(point: FloatArray) = gesture(listOf(point), 750)
    private fun gesture(points: List<FloatArray>, duration: Long, cancelBetween: Boolean = false) {
        onView(withId(R.id.read_view)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isDisplayed()
            override fun getDescription() = "Dispatch real reader touch events with bounded timing"
            override fun perform(uiController: UiController, view: View) {
                points.forEachIndexed { index, point ->
                    val down = SystemClock.uptimeMillis()
                    fun dispatch(action: Int) {
                        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point[0], point[1], 0)
                        try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
                    }
                    dispatch(MotionEvent.ACTION_DOWN)
                    uiController.loopMainThreadForAtLeast(duration)
                    dispatch(MotionEvent.ACTION_UP)
                    uiController.loopMainThreadForAtLeast(40)
                    if (cancelBetween && index == 0) {
                        dispatch(MotionEvent.ACTION_DOWN)
                        dispatch(MotionEvent.ACTION_CANCEL)
                    }
                }
            }
        })
    }

    private fun clearSelection() { scenario!!.onActivity { it.findViewById<ReadView>(R.id.read_view).cancelSelect() } }
    private fun noRulePopup() { onView(withText(R.string.highlight_rule_disable)).check(doesNotExist()) }
    private fun screenY(view: View): Int = IntArray(2).also(view::getLocationOnScreen)[1]
    private fun awaitReader(condition: (ReadView) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        do {
            var ready = false
            scenario!!.onActivity { ready = condition(it.findViewById(R.id.read_view)) }
            if (ready) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        var state = ""
        scenario!!.onActivity {
            val page = it.findViewById<ReadView>(R.id.read_view).curPage.textPage
            state = "book=${ReadBook.book?.bookUrl}; completed=${ReadBook.curTextChapter?.isCompleted}; " +
                "rules=${ReadBook.highlightRules.map { rule -> rule.pattern }}; page=${page.text.take(240)}"
        }
        screenshot("highlight-trigger-timeout")
        error("Reader did not reach the expected highlight state: $state")
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}
