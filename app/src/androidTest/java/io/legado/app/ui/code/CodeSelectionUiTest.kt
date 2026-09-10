package io.legado.app.ui.code

import android.app.Activity
import android.app.Instrumentation
import android.app.SearchManager
import android.content.ClipboardManager
import android.content.Intent
import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.MultiAutoCompleteTextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.children
import androidx.core.content.FileProvider
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.Tap
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow
import io.legado.app.R
import io.legado.app.help.CacheManager
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.association.FileAssociationActivity
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.association.ImportBookSourceViewModel
import io.legado.app.ui.association.ImportRssSourceDialog
import io.legado.app.ui.association.ImportRssSourceViewModel
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.utils.GSON
import io.legado.app.ui.widget.code.KeywordTokenizer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.allOf
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class CodeSelectionUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val cacheKey = "code-selection-${UUID.randomUUID()}"
    private val source = "const before = 1;\nfunction example() {\n  const message = \"你好 🌍\";\n  return message;\n}\nconst after = 2;"
    private var scenario: ActivityScenario<CodeEditActivity>? = null
    private val selectedText = source.substring(source.indexOf("function"), source.indexOf("\nconst after"))

    @After fun cleanUp() {
        scenario?.takeIf { it.state != Lifecycle.State.DESTROYED }?.onActivity { activity ->
            // A failed edit assertion must not leave the discard dialog blocking later tests.
            activity.findViewById<CodeEditor>(R.id.editText).takeIf { it.isShown }
                ?.setText(ViewModelProvider(activity)[CodeEditViewModel::class.java].initialText)
        }
        scenario?.close()
        CacheManager.deleteMemory(cacheKey)
    }

    @Test fun longPressKeepsMultilineSelectionAndSharesExactlyThatText() {
        launchEditor()
        selectFunction()
        val expectedLeft = source.indexOf("function")
        val expectedRight = source.indexOf("\nconst after")
        for ((line, column) in listOf(2 to 11, 3 to 5)) {
            dismissNativeMenu()
            // Separate independent long presses from the platform's double-tap gesture window.
            SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
            press(Tap.LONG, line, column)
            try {
                awaitEditor {
                    it.cursor.left == expectedLeft && it.cursor.right == expectedRight &&
                        !actions(it).isEnabled && !actions(it).isShowing
                }
            } catch (failure: AssertionError) {
                val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    File(context.getExternalFilesDir("ui-regression"), "code-selection-failed-$line-$column.png")
                        .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
                withEditor {
                    throw AssertionError("Long press at $line:$column: selection=${it.cursor.left}..${it.cursor.right}, " +
                        "expected=$expectedLeft..$expectedRight, panel=${actions(it).isShowing}", failure)
                }
            }
            withEditor { assertEquals(source, it.text.toString()) }
            assertNativeMenu()
        }
        screenshot("code-selection-preserved-share")
        shareAndAssert(selectedText)
        withEditor {
            assertEquals(expectedLeft, it.cursor.left)
            assertEquals(expectedRight, it.cursor.right)
            assertEquals(source, it.text.toString())
        }
    }

    @Test fun longPressOutsideSelectionSelectsAndSharesTheNewWord() {
        assertOutsideSelectionReselects(readOnly = false)
    }

    @Test fun readOnlyLongPressOutsideSelectionSelectsAndSharesTheNewWord() {
        assertOutsideSelectionReselects(readOnly = true)
    }

    private fun assertOutsideSelectionReselects(readOnly: Boolean) {
        launchEditor(readOnly)
        for ((line, column, word) in listOf(Triple(0, 8, "before"), Triple(5, 8, "after"))) {
            selectFunction()
            dismissNativeMenu()
            press(Tap.LONG, line, column)
            awaitEditor {
                selection(it) == word && !actions(it).isEnabled && !actions(it).isShowing
            }
            withEditor {
                assertEquals(source.indexOf(word), it.cursor.left)
                assertEquals(source.indexOf(word) + word.length, it.cursor.right)
                assertEquals(source, it.text.toString())
            }
            assertNativeMenu()
            screenshot("code-selection-outside-$word-${if (readOnly) "readonly" else "editable"}")
            shareAndAssert(word)
        }
    }

    @Test fun firstLongPressStillSelectsWordAndTapClearsSelection() {
        launchEditor()
        withEditor {
            it.setSelection(0, 0)
            actions(it).dismiss()
        }
        press(Tap.LONG, 2, 11)
        awaitEditor {
            it.cursor.isSelected && selection(it) == "message" && !actions(it).isShowing
        }
        assertNativeMenu()
        screenshot("code-selection-first-long-press")
        dismissNativeMenu()
        // A normal tap still clears the selection.
        press(Tap.SINGLE, 0, 2)
        awaitEditor { !it.cursor.isSelected && actions(it).isEnabled }
        withEditor { assertEquals(source, it.text.toString()) }
    }

    @Test fun readOnlyCodeCanShareSelectionWithoutExposingCutOrPaste() {
        launchEditor(readOnly = true)
        selectFunction()
        withEditor {
            assertFalse(it.isEditable)
            assertTrue(actions(it).isShowing)
            assertFalse(actions(it).view.findViewById<View>(io.github.rosemoe.sora.R.id.panel_btn_cut).isVisible)
            assertFalse(actions(it).view.findViewById<View>(io.github.rosemoe.sora.R.id.panel_btn_paste).isVisible)
        }
        screenshot("code-selection-read-only-share")
        shareAndAssert(selectedText)
        press(Tap.LONG, 2, 11)
        assertNativeMenu()
        onView(withText(android.R.string.cut)).inRoot(isPlatformPopup()).check(doesNotExist())
        onView(withText(android.R.string.paste)).inRoot(isPlatformPopup()).check(doesNotExist())
        screenshot("code-selection-read-only-native")
        shareAndAssert(selectedText)
        withEditor { assertEquals(source, it.text.toString()) }
    }

    @Test fun searchResultLongPressThenOutsideReselects() {
        launchEditor()
        scenario!!.onActivity { activity ->
            val search = CodeEditActivity::class.java.getDeclaredMethod("search")
            search.isAccessible = true
            search.invoke(activity)
            val searchTxt = CodeEditActivity::class.java.getDeclaredMethod("searchTxt", String::class.java)
            searchTxt.isAccessible = true
            searchTxt.invoke(activity, "function")
        }
        closeSoftKeyboard()
        awaitEditor { it.searcher.hasQuery() && it.searcher.matchedPositionCount > 0 }
        withEditor { assertTrue(it.searcher.gotoNext()) }
        awaitEditor { selection(it) == "function" }

        press(Tap.LONG, 1, 3)
        awaitEditor { selection(it) == "function" && !actions(it).isShowing }
        assertNativeMenu()
        dismissNativeMenu()
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)

        press(Tap.LONG, 0, 8)
        awaitEditor { selection(it) == "before" && !actions(it).isShowing }
        assertNativeMenu()
    }

    @Test fun nativeCopyCutPasteAndSelectAllUseTheCurrentSelection() {
        launchEditor()
        lateinit var clipboard: ClipboardManager
        withEditor {
            clipboard = it.context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("before copy", "clipboard sentinel"))
        }
        awaitEditor { it.hasWindowFocus() && clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "clipboard sentinel" }
        selectFunction(native = true)
        clickNativeAction(android.R.string.copy)
        awaitEditor { !it.cursor.isSelected && clipboard.primaryClip?.getItemAt(0)?.text?.toString() == selectedText }
        withEditor {
            assertEquals(selectedText, clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertEquals(source, it.text.toString())
            assertFalse(it.cursor.isSelected)
        }
        selectFunction(native = true)
        clickNativeAction(android.R.string.cut)
        val withoutFunction = source.replace(selectedText, "")
        awaitEditor { it.text.toString() == withoutFunction && clipboard.primaryClip?.getItemAt(0)?.text?.toString() == selectedText }
        withEditor {
            assertEquals(selectedText, clipboard.primaryClip!!.getItemAt(0).text.toString())
            it.setText(source)
            clipboard.setPrimaryClip(ClipData.newPlainText("native paste", "replacement 中文"))
        }
        selectFunction(native = true)
        clickNativeAction(android.R.string.paste)
        awaitEditor { it.text.toString() == source.replace(selectedText, "replacement 中文") }
        withEditor { it.setText(source) }
        selectFunction(native = true)
        clickNativeAction(android.R.string.selectAll)
        awaitEditor { selection(it) == source }
        screenshot("code-selection-native-select-all")
    }

    @Test fun nativeSearchSharesOnlySelectedTextAndBackKeepsTheEditorOpen() {
        launchEditor()
        selectFunction(native = true)
        val search = AtomicReference<Intent?>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_WEB_SEARCH) return null
                search.set(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            clickNativeAction(R.string.search)
            await { search.get() != null }
            assertEquals(selectedText, search.get()!!.getStringExtra(SearchManager.QUERY))
            withEditor { assertEquals(source, it.text.toString()) }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        press(Tap.LONG, 2, 11)
        assertNativeMenu()
        pressBack()
        withEditor {
            assertEquals(source, it.text.toString())
            assertEquals(selectedText, selection(it))
        }
        onView(withText(android.R.string.copy)).check(doesNotExist())
    }

    @Test fun longPressDragExtendsTheSelectionAndReturnsToTheEditorToolbar() {
        launchEditor()
        val from = editorPoint(2, 11)
        val to = editorPoint(3, 15)
        val down = SystemClock.uptimeMillis()
        fun touch(action: Int, point: FloatArray) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point[0], point[1], 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN, from)
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 100)
        awaitEditor { selection(it) == "message" }
        touch(MotionEvent.ACTION_MOVE, to)
        touch(MotionEvent.ACTION_UP, to)
        awaitEditor {
            it.cursor.left == source.indexOf("message") &&
                selection(it).contains("\n  return") && it.text.toString() == source
        }
        assertEditorToolbar()
        screenshot("code-selection-editor-drag")
    }

    @Test fun nativeMenuStaysClearOfKeyboardAndSearchToolsNearTheBottomAfterScrolling() {
        launchEditor()
        val code = (0..80).joinToString("\n") { "const line$it = $it;" }
        scenario!!.onActivity { activity ->
            activity.findViewById<CodeEditor>(R.id.editText).apply {
                setText(code)
                // The hosted emulator reports a hardware keyboard; allow the phone's IME path.
                setDisableSoftKbdIfHardKbdAvailable(false)
            }
            CodeEditActivity::class.java.getDeclaredMethod("search").apply { isAccessible = true }.invoke(activity)
        }
        onView(withId(R.id.editText)).perform(click())
        withEditor { it.showSoftInput() }
        awaitEditor { ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        var selected = ""
        var offset = 0
        var selectedLine = 0
        withEditor {
            val end = it.lastVisibleLine - 1
            assertTrue("The fixture must leave room for a multiline selection", end >= it.firstVisibleLine + 2)
            it.setSelectionRegion(end - 2, 0, end, 10, false)
            selected = selection(it)
            offset = it.offsetY
            selectedLine = end - 1
        }
        assertEditorToolbar()
        dismissEditorToolbar()
        press(Tap.LONG, selectedLine, 3)
        assertNativeMenu()
        assertNativeMenuClearOfTools()
        screenshot("code-selection-native-ime-search-bottom")
        onView(withId(R.id.editText)).perform(GeneralSwipeAction(Swipe.SLOW, { view ->
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            floatArrayOf(position[0] + view.width * 0.9f, position[1] + view.height * 0.4f)
        }, { view ->
            val editor = view as CodeEditor
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            floatArrayOf(position[0] + view.width * 0.9f, position[1] + view.height * 0.4f - editor.rowHeight)
        }, Press.FINGER))
        awaitEditor { it.offsetY > offset && selection(it) == selected && it.text.toString() == code }
        // FloatingActionMode deliberately hides a moving toolbar briefly before positioning it.
        SystemClock.sleep(500)
        assertNativeMenu()
        assertNativeMenuClearOfTools()
        screenshot("code-selection-native-ime-search-scrolled")
    }

    @Test fun ordinarySelectionKeepsEditorActionsUntilLongPressAndReturnsAfterTap() {
        launchEditor()
        selectFunction()
        screenshot("code-selection-ordinary-editor-toolbar")
        dismissEditorToolbar()
        press(Tap.LONG, 2, 11)
        assertNativeMenu()
        withEditor { assertEquals(selectedText, selection(it)) }
        screenshot("code-selection-long-press-native-toolbar")
        dismissNativeMenu()
        press(Tap.SINGLE, 0, 2)
        awaitEditor { !it.cursor.isSelected && actions(it).isEnabled }
        selectFunction()
        assertEditorToolbar()
        shareAndAssert(selectedText)
    }

    @Test fun longPressAtAnEmptyCaretShowsNativePasteAndInsertsClipboardText() {
        launchEditor()
        val clipboardText = "native caret paste 中文"
        withEditor {
            it.setText("")
            it.context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("caret paste", clipboardText))
        }
        press(Tap.LONG, 0, 0)
        awaitEditor { !it.cursor.isSelected && !actions(it).isEnabled && !actions(it).isShowing }
        onView(withText(android.R.string.paste)).inRoot(isPlatformPopup()).check(matches(isCompletelyDisplayed()))
        onView(withText(android.R.string.copy)).inRoot(isPlatformPopup()).check(doesNotExist())
        screenshot("code-selection-empty-native-paste")
        clickNativeAction(android.R.string.paste)
        awaitEditor { it.text.toString() == clipboardText && actions(it).isEnabled }
    }

    @Test fun savedDraftUsesTheCallersRequestedTransport() {
        val large = "中文 draft 🌍\n".repeat(2_000)
        for ((fileMode, expected) in listOf(true to large, false to large, true to "短文本")) {
            var returnedFile: String? = null
            try {
                launchEditor(forResult = true, fileMode = fileMode)
                withEditor { it.setText(expected) }
                awaitEditor { it.text.toString() == expected }
                onView(withId(R.id.menu_save)).perform(click())
                val result = scenario!!.result
                assertEquals(Activity.RESULT_OK, result.resultCode)
                val data = checkNotNull(result.resultData)
                returnedFile = data.getStringExtra("textFile")
                if (fileMode && expected == large) {
                    assertFalse("Large drafts must stay out of the result Bundle", data.hasExtra("text"))
                    assertNotNull(returnedFile)
                    assertEquals(expected, CodeTextTransfer.read(context, returnedFile!!))
                } else {
                    assertNull(returnedFile)
                    assertEquals(expected, data.getStringExtra("text"))
                }
            } finally {
                CodeTextTransfer.delete(context, returnedFile)
                scenario?.close()
                scenario = null
            }
        }
    }

    @Test fun sourcePreviewEditsReturnToTheSameBookAndRssCandidateWithoutLosingLongCode() {
        val id = UUID.randomUUID().toString()
        val script = "// 中文 🌍 original draft\n".repeat(2_000)
        val savedReplacement = AppConfig.importReplaceSource
        AppConfig.importReplaceSource = false
        try {
            for (rss in listOf(false, true)) {
                val urls = listOf("https://preview-$id.invalid/first", "https://preview-$id.invalid/second")
                val sources = if (rss) urls.mapIndexed { index, url ->
                    RssSource(sourceUrl = url, sourceName = "Preview $index", jsLib = script, ruleArticles = "article")
                } else urls.mapIndexed { index, url ->
                    BookSource(bookSourceUrl = url, bookSourceName = "Preview $index", jsLib = script,
                        searchUrl = "/search", ruleSearch = SearchRule(bookList = "article", name = "text"))
                }
                val file = File(context.cacheDir, "preview-editor-$id-$rss.json").apply {
                    writeText(GSON.toJson(sources))
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", file)
                val intent = Intent(context, FileAssociationActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri(file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                var editorActivity: CodeEditActivity? = null
                try {
                    ActivityScenario.launch<FileAssociationActivity>(intent).use { host ->
                        var parent: DialogFragment? = null
                        await {
                            host.onActivity { activity ->
                                parent = activity.supportFragmentManager.fragments.filterIsInstance<DialogFragment>()
                                    .find { if (rss) it is ImportRssSourceDialog else it is ImportBookSourceDialog }
                            }
                            var ready = false
                            instrumentation.runOnMainSync {
                                ready = parent?.view?.findViewById<RecyclerView>(R.id.recycler_view)
                                    ?.adapter?.itemCount == 2 && parent?.dialog?.window?.decorView?.hasWindowFocus() == true
                            }
                            ready
                        }
                        instrumentation.runOnMainSync {
                            val list = parent!!.requireView().findViewById<RecyclerView>(R.id.recycler_view)
                            checkNotNull(list.findViewHolderForAdapterPosition(1)).itemView
                                .findViewById<View>(R.id.tv_open).performClick()
                        }
                        var preview: CodeDialog? = null
                        await {
                            var ready = false
                            instrumentation.runOnMainSync {
                                preview = parent!!.childFragmentManager.fragments.filterIsInstance<CodeDialog>().firstOrNull()
                                ready = preview?.dialog?.window?.decorView?.hasWindowFocus() == true
                            }
                            ready
                        }
                        var original = ""
                        instrumentation.runOnMainSync {
                            assertEquals("1", preview!!.requestId)
                            original = preview!!.currentOriginalCode()
                            preview!!.setReplaceRuleRefreshPending(true)
                            assertFalse(preview!!.binding.toolBar.menu.findItem(R.id.menu_fullscreen_edit).isEnabled)
                            preview!!.setReplaceRuleRefreshPending(false)
                            preview!!.binding.toolBar.showOverflowMenu()
                        }
                        await {
                            var shown = false
                            instrumentation.runOnMainSync { shown = preview!!.binding.toolBar.isOverflowMenuShowing }
                            shown
                        }
                        onView(withText(R.string.view_in_code_editor)).inRoot(isPlatformPopup()).perform(click())
                        var inputPath: String? = null
                        await {
                            var ready = false
                            instrumentation.runOnMainSync {
                                editorActivity = ActivityLifecycleMonitorRegistry.getInstance()
                                    .getActivitiesInStage(Stage.RESUMED).filterIsInstance<CodeEditActivity>().firstOrNull()
                                val editor = editorActivity?.findViewById<CodeEditor>(R.id.editText)
                                ready = editor != null && editor.text.toString() == original && editor.isShown && editor.isEditable
                                if (ready) {
                                    inputPath = editorActivity!!.intent.getStringExtra("textFile")
                                    assertFalse(editorActivity!!.intent.hasExtra("text"))
                                    assertFalse(editorActivity!!.intent.getBooleanExtra("readOnly", false))
                                }
                            }
                            ready
                        }
                        assertNotNull("Long previews must use the actual transfer file", inputPath)
                        assertEquals(original, CodeTextTransfer.read(context, inputPath!!))
                        val insertion = original.indexOf(urls[1]) + urls[1].length
                        assertTrue(insertion > urls[1].length)
                        val edited = original.substring(0, insertion) + "#edited" + original.substring(insertion)
                        instrumentation.runOnMainSync {
                            val editor = editorActivity!!.findViewById<CodeEditor>(R.id.editText)
                            editor.text.replace(insertion, insertion, "#edited")
                            val position = editor.cursor.indexer.getCharPosition(insertion + "#edited".length)
                            editor.setSelection(position.line, position.column)
                            assertEquals(edited, editor.text.toString())
                            assertEquals(insertion + "#edited".length, editor.cursor.left)
                        }
                        val beforeRecreation = editorActivity
                        instrumentation.runOnMainSync { editorActivity!!.recreate() }
                        var recreationState = "No resumed editor"
                        await(message = { "Editor draft restoration: $recreationState" }) {
                            var ready = false
                            instrumentation.runOnMainSync {
                                editorActivity = ActivityLifecycleMonitorRegistry.getInstance()
                                    .getActivitiesInStage(Stage.RESUMED).filterIsInstance<CodeEditActivity>().firstOrNull()
                                val editor = editorActivity?.findViewById<CodeEditor>(R.id.editText)
                                recreationState = "newActivity=${editorActivity !== beforeRecreation}, " +
                                    "editable=${editor?.isEditable}, textMatches=${editor?.text?.toString() == edited}, " +
                                    "length=${editor?.text?.length}/${edited.length}, " +
                                    "cursor=${editor?.cursor?.left}/${insertion + "#edited".length}"
                                ready = editorActivity !== beforeRecreation && editor != null && editor.isEditable &&
                                    editor.text.toString() == edited && editor.cursor.left == insertion + "#edited".length
                            }
                            ready
                        }
                        onView(withId(R.id.menu_save)).perform(click())
                        await {
                            var ready = false
                            instrumentation.runOnMainSync {
                                ready = preview!!.currentOriginalCode() == edited &&
                                    preview!!.dialog?.window?.decorView?.hasWindowFocus() == true &&
                                    preview!!.binding.toolBar.menu.findItem(R.id.menu_fullscreen_edit).isEnabled
                            }
                            ready
                        }
                        assertFalse("The input transfer file must be removed after return", File(inputPath!!).exists())
                        instrumentation.waitForIdleSync()
                        checkNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
                            try {
                                File(context.getExternalFilesDir("ui-regression"), "source-preview-editor-return-$rss.png")
                                    .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                            } finally { bitmap.recycle() }
                        }
                        // Discard a second edit: only the cursor may return, never the discarded draft.
                        instrumentation.runOnMainSync { preview!!.binding.toolBar.showOverflowMenu() }
                        await {
                            var shown = false
                            instrumentation.runOnMainSync { shown = preview!!.binding.toolBar.isOverflowMenuShowing }
                            shown
                        }
                        onView(withText(R.string.view_in_code_editor)).inRoot(isPlatformPopup()).perform(click())
                        await {
                            var ready = false
                            instrumentation.runOnMainSync {
                                editorActivity = ActivityLifecycleMonitorRegistry.getInstance()
                                    .getActivitiesInStage(Stage.RESUMED).filterIsInstance<CodeEditActivity>().firstOrNull()
                                val editor = editorActivity?.findViewById<CodeEditor>(R.id.editText)
                                ready = editor?.text?.toString() == edited && editor.isEditable
                            }
                            ready
                        }
                        instrumentation.runOnMainSync {
                            editorActivity!!.findViewById<CodeEditor>(R.id.editText).text.replace(insertion, insertion, "discarded")
                            editorActivity!!.finish()
                        }
                        onView(withText(R.string.no)).inRoot(isDialog()).perform(click())
                        await {
                            var ready = false
                            instrumentation.runOnMainSync {
                                ready = preview!!.dialog?.window?.decorView?.hasWindowFocus() == true &&
                                    preview!!.binding.toolBar.menu.findItem(R.id.menu_fullscreen_edit).isEnabled
                                if (ready) assertEquals(edited, preview!!.currentOriginalCode())
                            }
                            ready
                        }
                        onView(withId(R.id.menu_save)).inRoot(isDialog()).perform(click())
                        await {
                            var ready = false
                            instrumentation.runOnMainSync {
                                if (rss) {
                                    val actual = ViewModelProvider(parent!!)[ImportRssSourceViewModel::class.java].allSources
                                    ready = actual.size == 2 && actual[1].sourceUrl == urls[1] + "#edited"
                                    if (ready) { assertEquals(urls[0], actual[0].sourceUrl); assertEquals(script, actual[1].jsLib) }
                                } else {
                                    val actual = ViewModelProvider(parent!!)[ImportBookSourceViewModel::class.java].allSources
                                    ready = actual.size == 2 && actual[1].bookSourceUrl == urls[1] + "#edited"
                                    if (ready) { assertEquals(urls[0], actual[0].bookSourceUrl); assertEquals(script, actual[1].jsLib) }
                                }
                            }
                            ready
                        }
                        if (rss) {
                            val derived = edited.replace("#edited", "#replacement-only")
                            val readOnlyPreview = CodeDialog(edited, false, "1", derived, showAlternate = true)
                            instrumentation.runOnMainSync {
                                readOnlyPreview.show(parent!!.childFragmentManager, "readonly-editor-entry")
                            }
                            await {
                                var ready = false
                                instrumentation.runOnMainSync {
                                    ready = readOnlyPreview.dialog?.window?.decorView?.hasWindowFocus() == true
                                }
                                ready
                            }
                            instrumentation.runOnMainSync {
                                assertTrue(readOnlyPreview.binding.cbSourceReplacementPreview.isChecked)
                                readOnlyPreview.binding.toolBar.showOverflowMenu()
                            }
                            await {
                                var shown = false
                                instrumentation.runOnMainSync { shown = readOnlyPreview.binding.toolBar.isOverflowMenuShowing }
                                shown
                            }
                            onView(withText(R.string.view_in_code_editor)).inRoot(isPlatformPopup()).perform(click())
                            await {
                                var ready = false
                                instrumentation.runOnMainSync {
                                    editorActivity = ActivityLifecycleMonitorRegistry.getInstance()
                                        .getActivitiesInStage(Stage.RESUMED).filterIsInstance<CodeEditActivity>().firstOrNull()
                                    val editor = editorActivity?.findViewById<CodeEditor>(R.id.editText)
                                    ready = editor != null && editor.text.toString() == derived && !editor.isEditable
                                }
                                ready
                            }
                            instrumentation.runOnMainSync { editorActivity!!.finish() }
                            await {
                                var ready = false
                                instrumentation.runOnMainSync {
                                    ready = readOnlyPreview.dialog?.window?.decorView?.hasWindowFocus() == true
                                    if (ready) assertEquals(edited, readOnlyPreview.currentOriginalCode())
                                }
                                ready
                            }
                            instrumentation.runOnMainSync { readOnlyPreview.dismiss() }
                        }
                    }
                } finally {
                    instrumentation.runOnMainSync { editorActivity?.takeUnless { it.isFinishing }?.finish() }
                    file.delete()
                }
            }
        } finally {
            AppConfig.importReplaceSource = savedReplacement
        }
    }

    @Test fun fileBackedReplacementPreviewIsReadOnlyInTheFullEditor() {
        val code = "// replacement preview 中文 🌍\n".repeat(1_000)
        val path = CodeTextTransfer.write(context, code)
        try {
            scenario = ActivityScenario.launchActivityForResult(Intent(context, CodeEditActivity::class.java).apply {
                putExtra("textFile", path)
                putExtra("useTextFile", true)
                putExtra("readOnly", true)
            })
            awaitEditor { it.isShown && it.text.toString() == code }
            withEditor { assertFalse(it.isEditable) }
            scenario!!.onActivity { it.finish() }
            assertEquals(Activity.RESULT_CANCELED, scenario!!.result.resultCode)
            assertEquals(code, CodeTextTransfer.read(context, path))
        } finally {
            CodeTextTransfer.delete(context, path)
        }
    }

    @Test fun safeEditorRetainsItsEditedDraftAndCursorAcrossRecreation() {
        val code = "\uFEFF// 中文 🌍\r\n" + "a" + "\u0301".repeat(12) + "\r\noriginal"
        val edited = code.replace("\r\n", "\n") + " 已修改"
        val cursor = edited.length - 2
        scenario = ActivityScenario.launchActivityForResult(Intent(context, CodeEditActivity::class.java).apply {
            putExtra("text", code)
            putExtra("useTextFile", true)
        })
        await {
            var ready = false
            scenario!!.onActivity { ready = ViewModelProvider(it)[CodeEditViewModel::class.java].editorDraft?.text == code }
            ready
        }
        scenario!!.onActivity { activity ->
            activity.findViewById<ViewGroup>(R.id.editorContainer).children.filterIsInstance<WebView>().single().evaluateJavascript(
                "editor.value = ${GSON.toJson(edited)}; editor.setSelectionRange($cursor, $cursor);" +
                    "editor.dispatchEvent(new Event('input'));", null,
            )
        }
        await {
            var ready = false
            scenario!!.onActivity {
                ready = ViewModelProvider(it)[CodeEditViewModel::class.java].editorDraft?.let { draft -> draft.text == edited && draft.cursorPosition == cursor } == true
            }
            ready
        }
        scenario!!.recreate()
        await {
            var ready = false
            scenario!!.onActivity {
                ready = ViewModelProvider(it)[CodeEditViewModel::class.java].editorDraft?.text == edited &&
                    it.findViewById<ViewGroup>(R.id.editorContainer).children.filterIsInstance<WebView>().any { web -> web.isShown }
            }
            ready
        }
        val restored = AtomicReference<String>()
        await {
            scenario!!.onActivity {
                it.findViewById<ViewGroup>(R.id.editorContainer).children.filterIsInstance<WebView>().single().evaluateJavascript(
                    "window.__getEditorState && window.__getEditorState();", restored::set,
                )
            }
            SafeEditorResultCodec.decode(restored.get())?.let { it.text == edited && it.cursorPosition == cursor && it.dirty } == true
        }
        onView(withId(R.id.menu_save)).perform(click())
        assertEquals(Activity.RESULT_OK, scenario!!.result.resultCode)
        assertEquals(edited, scenario!!.result.resultData.getStringExtra("text"))
        assertEquals(cursor, scenario!!.result.resultData.getIntExtra("cursorPosition", -1))
    }

    @Test fun nativePreviewTypesLongCodeWithoutRunningUnusedCompletion() {
        launchEditor()
        val code = "{\"jsLib\":\"" + "var value = '中文'; ".repeat(12_000) + "\"}"
        val dialog = CodeDialog(code, disableEdit = false)
        val timings = linkedMapOf<String, Long>()
        var tokenCalls = 0
        scenario!!.onActivity { dialog.show(it.supportFragmentManager, "native-input-cost") }
        try {
            await {
                var ready = false
                instrumentation.runOnMainSync { ready = dialog.dialog?.window?.decorView?.hasWindowFocus() == true }
                ready
            }
            instrumentation.runOnMainSync {
                dialog.binding.codeView.setTokenizer(object : MultiAutoCompleteTextView.Tokenizer {
                    override fun findTokenStart(text: CharSequence, cursor: Int): Int {
                        tokenCalls++
                        return KeywordTokenizer().findTokenStart(text, cursor)
                    }
                    override fun findTokenEnd(text: CharSequence, cursor: Int) = text.length
                    override fun terminateToken(text: CharSequence) = text
                })
                assertNull(dialog.binding.codeView.adapter)
            }
            val focusStart = SystemClock.uptimeMillis()
            onView(withId(R.id.code_view)).inRoot(isDialog()).perform(click())
            await {
                var visible = false
                instrumentation.runOnMainSync {
                    visible = ViewCompat.getRootWindowInsets(dialog.binding.codeView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                visible
            }
            timings["focusAndImeMs"] = SystemClock.uptimeMillis() - focusStart
            val offset = code.length - 2
            val frame = CountDownLatch(1)
            val inputStart = SystemClock.uptimeMillis()
            instrumentation.runOnMainSync {
                val view = dialog.binding.codeView
                val selectionStart = SystemClock.uptimeMillis()
                view.setSelection(offset)
                timings["selectionMs"] = SystemClock.uptimeMillis() - selectionStart
                assertTrue(view.isHardwareAccelerated)
                view.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }
                assertTrue(checkNotNull(view.onCreateInputConnection(EditorInfo())).commitText("中", 1))
                view.postInvalidateOnAnimation()
            }
            assertTrue("The typed character never reached a committed frame", frame.await(10, TimeUnit.SECONDS))
            timings["inputAndFrameMs"] = SystemClock.uptimeMillis() - inputStart
            instrumentation.runOnMainSync {
                assertEquals(code.substring(0, offset) + "中" + code.substring(offset), dialog.currentOriginalCode())
                assertEquals(0, tokenCalls)
            }
            val artifacts = checkNotNull(context.getExternalFilesDir("ui-regression"))
            File(artifacts, "native-preview-input.txt").writeText("characters=${code.length}\ntokenCalls=$tokenCalls\n$timings\n")
            checkNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
                try { File(artifacts, "native-preview-input.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
            assertTrue("Typing still stalls the native preview: $timings", timings.getValue("inputAndFrameMs") < 1_000)
            instrumentation.runOnMainSync {
                val view = dialog.binding.codeView
                view.setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, listOf("target")))
                view.setText("prefix target")
                view.setSelection(view.length())
                assertTrue(view.enoughToFilter())
                assertTrue("An installed completion adapter must still tokenize", tokenCalls > 0)
                view.dismissDropDown()
            }
        } finally {
            instrumentation.runOnMainSync { dialog.dismissAllowingStateLoss() }
        }
    }

    private fun launchEditor(readOnly: Boolean = false, forResult: Boolean = false, fileMode: Boolean = false) {
        val intent = Intent(context, CodeEditActivity::class.java).putExtra("title", "Code selection regression")
            .putExtra("useTextFile", fileMode)
        if (readOnly) {
            CacheManager.putMemory(cacheKey, source)
            intent.putExtra("cacheKey", cacheKey)
        } else {
            intent.putExtra("text", source)
        }
        scenario = if (forResult) ActivityScenario.launchActivityForResult(intent) else ActivityScenario.launch(intent)
        awaitEditor { it.isShown && it.width > 0 && it.text.toString() == source && it.hasFocus() }
        closeSoftKeyboard()
    }

    private fun selectFunction(native: Boolean = false) {
        withEditor { it.setSelectionRegion(1, 0, 4, 1, false) }
        awaitEditor { selection(it) == selectedText && actions(it).isEnabled && actions(it).isShowing }
        assertEditorToolbar()
        if (native) {
            dismissEditorToolbar()
            press(Tap.LONG, 2, 11)
            assertNativeMenu()
        }
    }

    private fun press(tap: Tap, line: Int, column: Int) {
        onView(withId(R.id.editText)).perform(GeneralClickAction(tap, { view ->
            val editor = view as CodeEditor
            val location = IntArray(2)
            editor.getLocationOnScreen(location)
            val point = floatArrayOf(location[0] + editor.getCharOffsetX(line, column) + editor.dpUnit,
                location[1] + editor.getCharOffsetY(line, column) - editor.rowHeight / 2f)
            val visible = Rect()
            assertTrue(editor.getGlobalVisibleRect(visible))
            assertTrue("Gesture must hit the visible editor at line $line, column $column",
                visible.contains(point[0].toInt(), point[1].toInt()))
            point
        }, Press.FINGER))
    }

    private fun editorPoint(line: Int, column: Int): FloatArray {
        var point = floatArrayOf()
        withEditor {
            val location = IntArray(2)
            it.getLocationOnScreen(location)
            point = floatArrayOf(location[0] + it.getCharOffsetX(line, column) + it.dpUnit,
                location[1] + it.getCharOffsetY(line, column) - it.rowHeight / 2f)
        }
        return point
    }

    @Suppress("DEPRECATION")
    private fun shareAndAssert(expected: String) {
        val chooser = AtomicReference<Intent?>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                chooser.set(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            var native = false
            withEditor { native = !actions(it).isEnabled }
            if (native) {
                clickNativeAction(R.string.share)
            } else {
                onView(withId(R.id.code_share_selection)).inRoot(isPlatformPopup()).perform(click())
            }
            await { chooser.get() != null }
            val send = chooser.get()!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            assertEquals(expected, send.getStringExtra(Intent.EXTRA_TEXT))
            assertNotEquals(source, send.getStringExtra(Intent.EXTRA_TEXT))
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun actions(editor: CodeEditor) = editor.getComponent(EditorTextActionWindow::class.java)

    private fun dismissEditorToolbar() {
        lateinit var popup: View
        withEditor {
            popup = actions(it).view.rootView
            actions(it).dismiss()
        }
        await { !popup.isAttachedToWindow }
        // Popup removal reaches InputDispatcher asynchronously; do not inject into its stale window.
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
    }

    private fun assertNativeMenu() {
        // Sora's image-button popup has no text labels. This checks the actual platform popup.
        onView(withText(android.R.string.copy)).inRoot(isPlatformPopup())
            .check(matches(isCompletelyDisplayed()))
        withEditor {
            assertFalse("The editor toolbar must not overlap Android's menu", actions(it).isShowing)
            assertFalse(actions(it).isEnabled)
        }
    }

    private fun assertEditorToolbar() {
        onView(withId(R.id.code_share_selection)).inRoot(isPlatformPopup())
            .check(matches(isCompletelyDisplayed()))
        withEditor {
            assertTrue(actions(it).isEnabled)
            assertTrue(actions(it).isShowing)
        }
        onView(withText(android.R.string.copy)).check(doesNotExist())
    }

    private fun assertNativeMenuClearOfTools() {
        val menuBounds = Rect()
        onView(withText(android.R.string.copy)).inRoot(isPlatformPopup()).check { view, failure ->
            if (failure != null) throw failure
            val nativeTextId = context.resources.getIdentifier("floating_toolbar_menu_item_text", "id", "android")
            assertEquals("The selection action must be rendered by Android", nativeTextId, view!!.id)
            val panel = view.parent.parent as View
            visibleScreenBounds(panel, menuBounds)
        }
        val toolBounds = Rect()
        onView(withId(R.id.recycler_view))
            .inRoot(withDecorView(hasDescendant(withId(R.id.recycler_view))))
            .check { view, failure ->
                if (failure != null) throw failure
                visibleScreenBounds(view!!, toolBounds)
            }
        assertFalse("Native menu $menuBounds overlaps keyboard tools $toolBounds", Rect.intersects(menuBounds, toolBounds))
        val keyboardBounds = Rect(toolBounds)
        scenario!!.onActivity { activity ->
            visibleScreenBounds(activity.findViewById(R.id.search_group), toolBounds)
            assertFalse("Native menu $menuBounds overlaps search tools $toolBounds", Rect.intersects(menuBounds, toolBounds))
        }
        File(context.getExternalFilesDir("ui-regression"), "code-selection-native-menu-bounds.txt")
            .appendText("menu=$menuBounds; keyboard=$keyboardBounds; search=$toolBounds\n")
    }

    private fun visibleScreenBounds(view: View, bounds: Rect) {
        // getGlobalVisibleRect is relative to this window's root, even for a PopupWindow.
        assertTrue(view.getGlobalVisibleRect(bounds))
        val origin = IntArray(2)
        view.rootView.getLocationOnScreen(origin)
        bounds.offset(origin[0], origin[1])
    }

    private fun dismissNativeMenu() {
        var native = false
        withEditor { native = !actions(it).isEnabled }
        // Exercise the real Back callback for native mode; Sora's nonmodal toolbar has no Back action.
        if (native) pressBack() else withEditor { actions(it).dismiss() }
        withEditor { assertTrue(it.isShown) }
    }

    private fun clickNativeAction(label: Int) {
        // Android keeps the overflow panel in the view tree while it is invisible.
        val visibleAction = allOf(withText(label), isDisplayed())
        try {
            onView(visibleAction).inRoot(isPlatformPopup()).check(matches(isCompletelyDisplayed()))
        } catch (_: NoMatchingViewException) {
            val overflow = context.resources.getIdentifier(
                "floating_toolbar_open_overflow_description", "string", "android"
            )
            assertNotEquals("Android's overflow accessibility label must exist", 0, overflow)
            onView(withContentDescription(context.getString(overflow))).inRoot(isPlatformPopup()).perform(click())
        }
        onView(visibleAction).inRoot(isPlatformPopup()).check(matches(isCompletelyDisplayed())).perform(click())
    }

    private fun selection(editor: CodeEditor) = editor.text.subSequence(editor.cursor.left, editor.cursor.right).toString()

    private fun withEditor(action: (CodeEditor) -> Unit) {
        scenario!!.onActivity { action(it.findViewById(R.id.editText)) }
    }

    private fun awaitEditor(condition: (CodeEditor) -> Boolean) = await {
        var ready = false
        withEditor { ready = condition(it) }
        ready
    }

    private fun await(
        message: () -> String = { "Code editor did not reach the expected state" },
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        val ready = condition()
        assertTrue(message(), ready)
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        // FloatingActionMode briefly hides the toolbar while the selection geometry moves.
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        val committed = CountDownLatch(1)
        scenario!!.onActivity { activity ->
            val root = activity.window.decorView
            assertTrue(root.isHardwareAccelerated)
            root.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            root.postInvalidateOnAnimation()
        }
        assertTrue("Editor frame was not committed", committed.await(5, TimeUnit.SECONDS))
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}
