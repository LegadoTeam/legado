package io.legado.app.ui.book.read

import android.content.pm.ActivityInfo
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.text.style.BackgroundColorSpan
import android.view.InputDevice
import android.view.MotionEvent
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.ui.about.AboutActivity
import io.legado.app.utils.hideSoftInput
import io.legado.app.utils.defaultSharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ContentEditSearchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.applicationContext
    private val id = UUID.randomUUID().toString()
    private val book = Book(
        bookUrl = "https://example.invalid/content-search/$id",
        origin = "https://example.invalid/source/$id",
        name = "Content search $id",
        author = "Fixture",
    )
    private val chapter = BookChapter(bookUrl = book.bookUrl, url = "${book.bookUrl}/0", title = "Chapter")
    private val content = "Alpha alpha ALPHA\na.b aXb\n" +
        (0 until 500).joinToString("\n") { "Line $it: editable chapter text for scrolling." }
    private var scenario: ActivityScenario<AboutActivity>? = null
    private val originalPlainText = context.defaultSharedPreferences.all["contentEditPlainText"] as? Boolean

    @Before
    fun setUp() {
        context.defaultSharedPreferences.edit().remove("contentEditPlainText").commit()
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(chapter)
        BookHelp.saveText(book, chapter, content)
        scenario = ActivityScenario.launch(AboutActivity::class.java)
        scenario!!.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            ContentEditDialog().apply {
                arguments = Bundle().apply {
                    putString("bookUrl", book.bookUrl)
                    putInt("chapterIndex", 0)
                    putInt("chapterPos", 0)
                    putString("title", "Content search")
                }
            }.showNow(activity.supportFragmentManager, "content-search")
        }
        awaitDialog { it.viewModel.hasDraft && it.binding.contentView.text!!.contains("Line 499") }
    }

    @After
    fun tearDown() {
        scenario?.close()
        context.defaultSharedPreferences.edit().apply {
            originalPlainText?.let { putBoolean("contentEditPlainText", it) } ?: remove("contentEditPlainText")
        }.commit()
        BookHelp.delContent(book, chapter)
        appDb.bookDao.delete(book)
    }

    @Test
    fun plainTextEditsCopyAndColdRestorationKeepImageCodeInTheSavedDraft() {
        val image = "<img src=\"https://example.invalid/body.png\">"
        val bubble = """<img src="data:image/svg+xml;base64,QQ,{'style':'TEXT','click':'getDP(1)'}">"""
        val raw = "甲${image}乙${bubble}丙\n普通 <内容>"
        var edited = ""
        onDialog { dialog ->
            val menu = dialog.binding.toolBar.menu
            assertFalse(menu.findItem(R.id.menu_content_plain_text).isChecked)
            dialog.viewModel.updateDraft(raw)
            assertEquals(raw, dialog.binding.contentView.text.toString())
            assertTrue(menu.performIdentifierAction(R.id.menu_content_plain_text, 0))
            assertEquals("甲乙丙\n普通 <内容>", dialog.binding.contentView.text.toString())
            assertEquals(raw, dialog.viewModel.draftText)
            assertTrue(menu.performIdentifierAction(R.id.menu_copy_all, 0))
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            assertEquals("Content search\n甲乙丙\n普通 <内容>", clipboard.primaryClip!!.getItemAt(0).text.toString())
            dialog.binding.contentView.text!!.replace(1, 2, "替换")
            edited = "甲${image}替换${bubble}丙\n普通 <内容>"
            assertEquals(edited, dialog.viewModel.draftText)
            assertEquals("甲替换丙\n普通 <内容>", dialog.binding.contentView.text.toString())
            assertTrue(menu.performIdentifierAction(R.id.menu_content_plain_text, 0))
            assertEquals(edited, dialog.binding.contentView.text.toString())
            assertTrue(menu.performIdentifierAction(R.id.menu_copy_all, 0))
            assertEquals("Content search\n$edited", clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertTrue(menu.performIdentifierAction(R.id.menu_content_plain_text, 0))
            openSearch(dialog)
            dialog.binding.searchInput.setText("替换")
        }
        awaitCount("1/1")
        screenshot("content-plain-text-edited")
        // A new Fragment gets a new ViewModel: only its saved canonical draft can retain the tags.
        scenario!!.onActivity { activity ->
            val manager = activity.supportFragmentManager
            val previous = manager.findFragmentByTag("content-search") as ContentEditDialog
            val state = manager.saveFragmentInstanceState(previous)
            val args = Bundle(previous.requireArguments())
            previous.dismissNow()
            ContentEditDialog().apply {
                arguments = args
                setInitialSavedState(state)
            }.showNow(manager, "content-search")
        }
        awaitCount("1/1")
        onDialog {
            assertEquals(edited, it.viewModel.draftText)
            assertTrue(it.viewModel.hasChanges)
            assertTrue(it.binding.toolBar.menu.findItem(R.id.menu_content_plain_text).isChecked)
            assertEquals("甲替换丙\n普通 <内容>", it.binding.contentView.text.toString())
        }
        scenario!!.recreate()
        awaitCount("1/1")
        onDialog {
            assertEquals(edited, it.viewModel.draftText)
            assertTrue(it.binding.toolBar.menu.performIdentifierAction(R.id.menu_save, 0))
        }
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (BookHelp.getContent(book, chapter) != edited && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
        assertEquals(edited, BookHelp.getContent(book, chapter))
        assertTrue(context.defaultSharedPreferences.getBoolean("contentEditPlainText", false))
    }

    @Test
    fun hiddenImageCaretStaysCollapsedWhenSwitchingAndRestoringRawMode() {
        val image = "<img src=\"https://example.invalid/body.png\">"
        val raw = "甲${image}乙"
        onDialog {
            val ui = it.binding
            it.viewModel.updateDraft(raw)
            ui.toolBar.menu.performIdentifierAction(R.id.menu_content_plain_text, 0)
            ui.contentView.setSelection(1)
            ui.toolBar.menu.performIdentifierAction(R.id.menu_content_plain_text, 0)
            assertEquals(1 + image.length, ui.contentView.selectionStart)
            assertEquals(ui.contentView.selectionStart, ui.contentView.selectionEnd)
            ui.contentView.text!!.replace(ui.contentView.selectionStart, ui.contentView.selectionEnd, "新")
            assertEquals("甲${image}新乙", it.viewModel.draftText)
            it.viewModel.updateDraft(raw)
            ui.toolBar.menu.performIdentifierAction(R.id.menu_content_plain_text, 0)
            ui.contentView.setSelection(1)
        }
        // A preference change while the view is being recreated must not expand the saved caret.
        context.defaultSharedPreferences.edit().putBoolean("contentEditPlainText", false).commit()
        scenario!!.recreate()
        onDialog {
            val edit = it.binding.contentView
            assertEquals(raw, edit.text.toString())
            assertEquals(1 + image.length, edit.selectionStart)
            assertEquals(edit.selectionStart, edit.selectionEnd)
            edit.text!!.replace(edit.selectionStart, edit.selectionEnd, "新")
            assertEquals("甲${image}新乙", it.viewModel.draftText)
        }
    }

    @Test
    fun resettingContentInvalidatesCompletedAndPendingSearches() {
        onDialog {
            it.binding.contentView.text!!.append("\nunique edited tail")
            openSearch(it)
            it.binding.searchInput.setText("unique edited tail")
        }
        awaitCount("1/1")
        onDialog { it.binding.toolBar.menu.performIdentifierAction(R.id.menu_reset, 0) }
        // This source-less fixture resets to empty; its old match is beyond the new draft.
        awaitDialog { it.viewModel.draftText == "" && it.binding.searchCount.text.toString() == "0/0" }
        onDialog {
            assertFalse(it.binding.btnSearchNext.isEnabled)
            it.binding.btnSearchNext.performClick()
            it.viewModel.updateDraft("pending needle")
            it.binding.searchInput.setText("needle")
            it.viewModel.updateDraft("short")
        }
        awaitDialog(stableMillis = 300) { it.binding.searchCount.text.toString() == "0/0" }
        onDialog {
            assertFalse(it.binding.btnSearchNext.isEnabled)
            assertEquals("short", it.binding.contentView.text.toString())
        }
    }

    @Test
    fun literalRegexCaseAndCircularNavigationDoNotEditTheDraft() {
        var original = ""
        onDialog {
            original = it.binding.contentView.text.toString()
            assertFalse(it.viewModel.hasChanges)
            openSearch(it)
            it.binding.searchInput.setText("alpha")
        }
        awaitCount("1/3")
        onDialog { it.binding.btnSearchNext.performClick() }
        awaitCount("2/3")
        onDialog { it.binding.btnSearchNext.performClick() }
        awaitCount("3/3")
        onDialog { it.binding.btnSearchNext.performClick() }
        awaitCount("1/3")
        onDialog { it.binding.btnSearchPrev.performClick() }
        awaitCount("3/3")
        onDialog {
            it.binding.contentView.setSelection(0)
            it.binding.searchMatchCase.isChecked = true
        }
        awaitCount("1/1")
        onDialog { it.binding.searchInput.setText("a.b") }
        awaitCount("1/1")
        onDialog { it.binding.searchRegex.isChecked = true }
        awaitCount("1/2")
        onDialog { it.binding.searchInput.setText("[") }
        awaitDialog { it.binding.searchInput.error != null }
        onDialog {
            assertFalse(it.binding.btnSearchNext.isEnabled)
            assertEquals("0/0", it.binding.searchCount.text.toString())
            it.binding.searchInput.setText("(?=Alpha)")
        }
        awaitCount("1/1")
        onDialog {
            assertEquals(it.binding.contentView.selectionStart, it.binding.contentView.selectionEnd)
            assertEquals(original, it.binding.contentView.text.toString())
            assertFalse(it.viewModel.hasChanges)
        }
    }

    @Test
    fun editsRefreshMatchesWithoutMovingSelectionAndSaveTheActualDraft() {
        onDialog {
            openSearch(it)
            it.binding.searchInput.setText("alpha")
        }
        awaitCount("1/3")
        var selection = 0
        var scrollY = 0
        var edited = ""
        onDialog {
            it.binding.contentView.text!!.insert(0, "Alpha ")
            selection = it.binding.contentView.selectionStart
            scrollY = it.binding.contentView.scrollY
            edited = it.binding.contentView.text.toString()
        }
        awaitDialog { it.binding.searchCount.text.toString().endsWith("/4") }
        onDialog {
            assertEquals(selection, it.binding.contentView.selectionStart)
            assertEquals(scrollY, it.binding.contentView.scrollY)
            assertEquals(edited, it.viewModel.draftText)
            assertTrue(it.viewModel.hasChanges)
            assertTrue(it.binding.toolBar.menu.performIdentifierAction(R.id.menu_save, 0))
        }
        val deadline = SystemClock.uptimeMillis() + 10000
        while (BookHelp.getContent(book, chapter) != edited && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
        }
        assertEquals(edited, BookHelp.getContent(book, chapter))
    }

    @Test
    fun positionBarDragsTextWithoutMovingCaretAndUpdatesAfterEdits() {
        awaitDialog { it.binding.positionBar.isEnabled }
        var caret = 0
        var x = 0f
        var startY = 0f
        var endY = 0f
        onDialog {
            val ui = it.binding
            assertFalse(ui.searchBar.isVisible)
            caret = ui.contentView.selectionStart
            val location = IntArray(2)
            ui.positionBarContainer.getLocationOnScreen(location)
            x = location[0] + ui.positionBarContainer.width / 2f
            startY = location[1] + ui.positionBarContainer.height * 0.1f
            endY = location[1] + ui.positionBarContainer.height * 0.85f
        }
        val down = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x,
                if (index == 0) startY else endY, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        awaitDialog { it.binding.positionBar.progress > 6500 }
        onDialog {
            assertEquals(caret, it.binding.contentView.selectionStart)
            assertTrue(it.binding.contentView.scrollY > 0)
            it.binding.contentView.text!!.append("\n" + "More text\n".repeat(500))
        }
        instrumentation.waitForIdleSync()
        onDialog {
            val ui = it.binding
            val max = ui.contentView.layout.height + ui.contentView.totalPaddingTop +
                ui.contentView.totalPaddingBottom - ui.contentView.height
            val expected = (ui.contentView.scrollY.coerceIn(0, max) * 10000L / max).toInt()
            assertEquals(expected, ui.positionBar.progress)
            ui.contentView.setText("Short text")
        }
        awaitDialog { !it.binding.positionBar.isEnabled && it.binding.positionBar.progress == 0 }
        onDialog { it.binding.contentView.text!!.append("\n" + "More text\n".repeat(500)) }
        awaitDialog { it.binding.positionBar.isEnabled }
        onDialog { it.binding.contentView.text!!.clear() }
        awaitDialog { !it.binding.positionBar.isEnabled && it.binding.positionBar.progress == 0 }
    }

    @Test
    fun recreationRetainsSearchDraftAndPositionWithPortraitAndLandscapeScreenshots() {
        onDialog {
            it.binding.contentView.text!!.insert(0, "Edited draft\n")
            openSearch(it)
            it.binding.searchRegex.isChecked = true
            it.binding.searchMatchCase.isChecked = true
            it.binding.searchInput.setText("Line 220:")
        }
        awaitCount("1/1")
        instrumentation.waitForIdleSync()
        var edited = ""
        var scrollY = 0
        onDialog {
            edited = it.binding.contentView.text.toString()
            scrollY = it.binding.contentView.scrollY
            assertTrue(scrollY > 0)
        }
        screenshot("content-search-portrait")
        scenario!!.recreate()
        awaitCount("1/1")
        onDialog {
            assertEquals(edited, it.binding.contentView.text.toString())
            assertTrue(it.viewModel.hasChanges)
            assertTrue(it.binding.searchRegex.isChecked)
            assertTrue(it.binding.searchMatchCase.isChecked)
            assertEquals("Line 220:", it.binding.searchInput.text.toString())
            assertTrue("Restored scroll position", abs(scrollY - it.binding.contentView.scrollY) <= 6)
            val text = it.binding.contentView.text!!
            assertEquals(1, text.getSpans(0, text.length, BackgroundColorSpan::class.java).size)
        }
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        awaitDialog(stableMillis = 300) {
            it.binding.root.width > it.binding.root.height &&
                it.binding.searchCount.text.toString() == "1/1"
        }
        onDialog {
            val ui = it.binding
            assertTrue(ui.contentView.height > 0)
            assertTrue(ui.searchInput.width > 0)
            assertTrue(ui.searchBar.bottom <= ui.contentView.top)
            assertTrue(ui.contentView.right <= ui.positionBarContainer.left)
            assertEquals(edited, ui.contentView.text.toString())
        }
        screenshot("content-search-landscape")
    }

    private fun openSearch(dialog: ContentEditDialog) {
        assertTrue(dialog.binding.toolBar.menu.performIdentifierAction(R.id.menu_search, 0))
        dialog.binding.searchInput.hideSoftInput()
    }

    private fun onDialog(action: (ContentEditDialog) -> Unit) {
        scenario!!.onActivity {
            action(it.supportFragmentManager.findFragmentByTag("content-search") as ContentEditDialog)
        }
    }

    private fun awaitCount(count: String) = awaitDialog { it.binding.searchCount.text.toString() == count }

    private fun awaitDialog(stableMillis: Long = 0, condition: (ContentEditDialog) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        var satisfiedSince: Long? = null
        do {
            var satisfied = false
            scenario!!.onActivity {
                val dialog = it.supportFragmentManager.findFragmentByTag("content-search") as? ContentEditDialog
                if (dialog?.view != null) satisfied = condition(dialog)
            }
            if (satisfied) {
                val now = SystemClock.uptimeMillis()
                if (satisfiedSince == null) satisfiedSince = now
                if (now - satisfiedSince >= stableMillis) return
            } else {
                satisfiedSince = null
            }
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        var state = ""
        onDialog { state = "count=${it.binding.searchCount.text}, error=${it.binding.searchInput.error}, " +
            "length=${it.binding.contentView.length()}, scroll=${it.binding.contentView.scrollY}, " +
            "bar=${it.binding.positionBar.progress}, enabled=${it.binding.positionBar.isEnabled}" }
        throw AssertionError("Timed out: $state")
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
