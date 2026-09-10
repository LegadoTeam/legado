package io.legado.app.ui.code

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
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
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withText
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
        scenario?.close()
        CacheManager.deleteMemory(cacheKey)
    }

    @Test fun longPressKeepsMultilineSelectionAndSharesExactlyThatText() {
        launchEditor()
        selectFunction()
        val expectedLeft = source.indexOf("function")
        val expectedRight = source.indexOf("\nconst after")
        for ((line, column) in listOf(2 to 11, 3 to 5)) {
            withEditor { actions(it).dismiss() }
            // Separate independent long presses from the platform's double-tap gesture window.
            SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
            press(Tap.LONG, line, column)
            try {
                awaitEditor {
                    it.cursor.left == expectedLeft && it.cursor.right == expectedRight &&
                        actions(it).isShowing && shareButton(it).isShown
                }
            } catch (failure: AssertionError) {
                val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    File(context.getExternalFilesDir("ui-regression"), "code-selection-failed-$line-$column.png")
                        .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
                withEditor {
                    throw AssertionError("Long press at $line:$column: selection=${it.cursor.left}..${it.cursor.right}, " +
                        "expected=$expectedLeft..$expectedRight, panel=${actions(it).isShowing}, share=${shareButton(it).isShown}", failure)
                }
            }
            withEditor { assertEquals(source, it.text.toString()) }
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
            withEditor { actions(it).dismiss() }
            press(Tap.LONG, line, column)
            awaitEditor {
                selection(it) == word && actions(it).isShowing && shareButton(it).isShown
            }
            withEditor {
                assertEquals(source.indexOf(word), it.cursor.left)
                assertEquals(source.indexOf(word) + word.length, it.cursor.right)
                assertEquals(source, it.text.toString())
            }
            screenshot("code-selection-outside-$word-${if (readOnly) "readonly" else "editable"}")
            shareAndAssert(word)
        }
    }

    @Test fun firstLongPressStillSelectsWordAndTapClearsSelection() {
        launchEditor()
        withEditor {
            it.setSelection(0, 0)
            actions(it).dismiss()
            assertFalse(shareButton(it).isVisible)
        }
        press(Tap.LONG, 2, 11)
        awaitEditor {
            it.cursor.isSelected && selection(it) == "message" && actions(it).isShowing && shareButton(it).isShown
        }
        screenshot("code-selection-first-long-press")
        withEditor { actions(it).dismiss() }
        // A normal tap still clears the selection.
        press(Tap.SINGLE, 0, 2)
        awaitEditor { !it.cursor.isSelected && !shareButton(it).isVisible }
        withEditor { assertEquals(source, it.text.toString()) }
    }

    @Test fun readOnlyCodeCanShareSelectionWithoutExposingCutOrPaste() {
        launchEditor(readOnly = true)
        selectFunction()
        withEditor {
            assertFalse(it.isEditable)
            assertFalse(actions(it).view.findViewById<View>(io.github.rosemoe.sora.R.id.panel_btn_cut).isVisible)
            assertFalse(actions(it).view.findViewById<View>(io.github.rosemoe.sora.R.id.panel_btn_paste).isVisible)
        }
        screenshot("code-selection-read-only-share")
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
        awaitEditor { selection(it) == "function" && actions(it).isShowing }
        withEditor { actions(it).dismiss() }
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)

        press(Tap.LONG, 0, 8)
        awaitEditor { selection(it) == "before" && actions(it).isShowing }
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
                assertFalse("Code preview must not invoke prose spell checking", dialog.binding.codeView.isSuggestionsEnabled)
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
                assertFalse("Code completion must not enable prose spell checking", view.isSuggestionsEnabled)
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
        // The activity restores its initial cursor after 360ms; start gestures after that real callback.
        val restored = CountDownLatch(1)
        withEditor { it.postDelayed({ restored.countDown() }, 450) }
        assertTrue(restored.await(5, TimeUnit.SECONDS))
        closeSoftKeyboard()
    }

    private fun selectFunction() {
        withEditor { it.setSelectionRegion(1, 0, 4, 1, false) }
        awaitEditor { selection(it) == selectedText && actions(it).isShowing && shareButton(it).isShown }
        onView(withId(R.id.code_share_selection)).inRoot(isPlatformPopup())
            .check(matches(isCompletelyDisplayed()))
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
            onView(withId(R.id.code_share_selection)).inRoot(isPlatformPopup())
                .check(matches(isCompletelyDisplayed())).perform(click())
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

    private fun shareButton(editor: CodeEditor) = actions(editor).view.findViewById<View>(R.id.code_share_selection)

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
        val committed = CountDownLatch(2)
        scenario!!.onActivity { activity ->
            val editor = activity.findViewById<CodeEditor>(R.id.editText)
            listOf(activity.window.decorView, actions(editor).view.rootView).forEach { root ->
                assertTrue(root.isHardwareAccelerated)
                root.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                root.postInvalidateOnAnimation()
            }
        }
        assertTrue("Editor and text action panel frames were not committed", committed.await(5, TimeUnit.SECONDS))
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}
