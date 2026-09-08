package io.legado.app.ui.code

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewConfiguration
import androidx.core.view.isVisible
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow
import io.legado.app.R
import io.legado.app.help.CacheManager
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

    private fun launchEditor(readOnly: Boolean = false) {
        val intent = Intent(context, CodeEditActivity::class.java).putExtra("title", "Code selection regression")
        if (readOnly) {
            CacheManager.putMemory(cacheKey, source)
            intent.putExtra("cacheKey", cacheKey)
        } else {
            intent.putExtra("text", source)
        }
        scenario = ActivityScenario.launch(intent)
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

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("Code editor did not reach the expected state", condition())
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
