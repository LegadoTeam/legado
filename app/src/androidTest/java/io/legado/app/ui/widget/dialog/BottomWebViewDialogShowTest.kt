package io.legado.app.ui.widget.dialog

import android.graphics.Bitmap
import android.os.SystemClock
import android.graphics.Rect
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.R as MaterialR
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.webView.PooledWebView
import io.legado.app.ui.about.AboutActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class BottomWebViewDialogShowTest {

    private val source = BookSource(
        bookSourceUrl = "https://example.invalid/dialog-test/${UUID.randomUUID()}",
        bookSourceName = "Dialog regression fixture",
        enabled = false,
        enabledExplore = false,
    )
    private var scenario: ActivityScenario<AboutActivity>? = null

    @Before
    fun setUp() {
        appDb.bookSourceDao.insert(source)
        scenario = ActivityScenario.launch(AboutActivity::class.java)
    }

    @After
    fun tearDown() {
        try {
            scenario?.close()
        } finally {
            appDb.bookSourceDao.delete(source.bookSourceUrl)
        }
    }

    @Test
    fun repeatedRequestsDoNotStackAndDismissAllowsImmediateReopen() {
        scenario!!.onActivity { activity ->
            val manager = activity.supportFragmentManager
            val first = newDialog()
            first.show(manager, "first")
            newDialog("other").show(manager, "other")
            newDialog().show(manager, "duplicate-with-different-tag")
            assertEquals(2, visibleDialogs(manager))

            first.dismiss()
            val reopened = newDialog()
            reopened.show(manager, "reopened")
            assertFalse(first.dialog?.isShowing == true)
            assertTrue(reopened.dialog?.isShowing == true)
            assertEquals(2, visibleDialogs(manager))
        }
    }

    @Test
    fun queuedBackgroundRequestsOpenOnlyOneWindow() {
        lateinit var manager: FragmentManager
        scenario!!.onActivity { manager = it.supportFragmentManager }
        val workers = List(3) {
            thread { newDialog().show(manager, "background") }
        }
        workers.forEach { it.join() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario!!.onActivity {
            assertEquals(1, visibleDialogs(manager))
        }
    }

    @Test
    fun stoppedAndDestroyedHostsRejectNewWindows() {
        lateinit var manager: FragmentManager
        scenario!!.moveToState(Lifecycle.State.CREATED).onActivity {
            manager = it.supportFragmentManager
            assertTrue(manager.isStateSaved)
            newDialog().show(manager, "stopped")
            assertEquals(0, visibleDialogs(manager))
        }
        scenario!!.close()
        scenario = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(manager.isDestroyed)
            newDialog().show(manager, "destroyed")
            assertEquals(0, visibleDialogs(manager))
        }
    }

    @Test
    fun bottomSheetWindowAlignsToBottom() {
        scenario!!.onActivity { activity ->
            val dialog = newDialog(config = """{"state":3}""")
            dialog.show(activity.supportFragmentManager, "bottom-alignment")
        }
        awaitGeometry { it.height > 0 && it.state == BottomSheetBehavior.STATE_EXPANDED }
        scenario!!.onActivity {
            val dialog = it.supportFragmentManager.fragments
                .filterIsInstance<BottomWebViewDialog>()
                .single { fragment -> fragment.dialog?.isShowing == true }
            val window = checkNotNull(dialog.dialog?.window)
            val sheet = checkNotNull(dialog.dialog?.findViewById<View>(MaterialR.id.design_bottom_sheet))
            assertTrue(abs(window.decorView.height - sheet.bottom) <= 2)

            dialog.upConfig("{\"dialogHeight\":240}")
            dialog.upConfig("{\"dialogHeight\":480}")
        }
        awaitGeometry { it.height == 480 && it.state == BottomSheetBehavior.STATE_EXPANDED }
        scenario!!.onActivity {
            val dialog = it.supportFragmentManager.fragments
                .filterIsInstance<BottomWebViewDialog>()
                .single { fragment -> fragment.dialog?.isShowing == true }
            val window = checkNotNull(dialog.dialog?.window)
            val sheet = checkNotNull(dialog.dialog?.findViewById<View>(MaterialR.id.design_bottom_sheet))
            assertTrue(abs(window.decorView.height - sheet.bottom) <= 2)
        }
    }

    @Test
    fun configuredHeightsStayAnchoredToBottom() {
        scenario!!.onActivity { activity ->
            newDialog(config = """{"state":3}""")
                .show(activity.supportFragmentManager, "configured-height")
        }

        val initial = awaitGeometry { it.height > 0 && it.state == BottomSheetBehavior.STATE_EXPANDED }
        applyConfig("{\"dialogHeight\":480}")
        val short = awaitGeometry { it.height == 480 }
        applyConfig("{\"dialogHeight\":560}")
        val tall = awaitGeometry { it.height > short.height && it.state == BottomSheetBehavior.STATE_EXPANDED }

        assertTrue(initial.toString(), abs(initial.bottomGap) <= 2)
        assertTrue(short.toString(), abs(short.bottomGap) <= 2)
        assertTrue(tall.toString(), abs(tall.bottomGap) <= 2)
        assertTrue(tall.top < short.top)
    }

    @Test
    fun percentageHeightWithoutFitToContentsStaysAtBottom() {
        scenario!!.onActivity { activity ->
            // Relevant configuration from the source attached to issue #1135.
            newDialog(config = """{"heightPercentage":0.75,"setFitToContents":false,
                "isGestureInsetBottomIgnored":true,"isHideable":true}""")
                .show(activity.supportFragmentManager, "source-height")
        }
        val initial = awaitGeometry {
            !it.fitToContents && it.state == BottomSheetBehavior.STATE_EXPANDED &&
                it.height > 0 && it.height < it.parentHeight
        }
        assertTrue(initial.toString(), abs(initial.bottomGap) <= 2)
        screenshot("paragraph-sheet-75-percent")

        applyConfig("""{"heightPercentage":0.5,"setFitToContents":false}""")
        val resized = awaitGeometry { !it.fitToContents && it.height < initial.height }
        assertTrue(resized.toString(), abs(resized.bottomGap) <= 2)
        assertTrue(resized.toString(), resized.top > initial.top)

        applyConfig("""{"heightPercentage":0.25,"setFitToContents":false}""")
        val quarter = awaitGeometry { it.height < resized.height }
        assertTrue(quarter.toString(), abs(quarter.bottomGap) <= 2)
        applyConfig("""{"state":6}""")
        val half = awaitGeometry { it.state == BottomSheetBehavior.STATE_HALF_EXPANDED }
        assertTrue(half.toString(), abs(half.bottomGap) <= 2)
        screenshot("paragraph-sheet-25-percent")

        applyConfig("""{"dialogHeight":-1,"setFitToContents":false}""")
        applyConfig("""{"state":3}""")
        val full = awaitGeometry {
            it.height == it.parentHeight && it.state == BottomSheetBehavior.STATE_EXPANDED
        }
        assertTrue(full.toString(), abs(full.top) <= 2 && abs(full.bottomGap) <= 2)
    }

    @Test
    fun measuredHeightAndFitModeChangesStayAtBottom() {
        scenario!!.onActivity { activity ->
            newDialog(config = """{"dialogHeight":480,"maxHeight":240,"setFitToContents":false}""")
                .show(activity.supportFragmentManager, "maximum-height")
        }
        val limited = awaitGeometry {
            it.height == 240 && !it.fitToContents &&
                it.state == BottomSheetBehavior.STATE_EXPANDED && abs(it.bottomGap) <= 2
        }
        assertTrue(limited.toString(), abs(limited.bottomGap) <= 2)

        applyConfig("""{"setFitToContents":true}""")
        val fitted = awaitGeometry { it.fitToContents && it.state == BottomSheetBehavior.STATE_EXPANDED }
        assertTrue(fitted.toString(), abs(fitted.bottomGap) <= 2)
        applyConfig("""{"setFitToContents":false}""")
        val unfitted = awaitGeometry {
            !it.fitToContents && it.state == BottomSheetBehavior.STATE_EXPANDED &&
                abs(it.bottomGap) <= 2
        }
        assertTrue(unfitted.toString(), abs(unfitted.bottomGap) <= 2)
    }

    @Test
    fun explicitOffsetSurvivesSparseUpdatesAndFullScreen() {
        lateinit var browser: BottomWebViewDialog
        lateinit var chrome: BottomWebViewDialog.CustomWebChromeClient
        scenario!!.onActivity { activity ->
            browser = newDialog(config = """{"dialogHeight":480,"setFitToContents":false,
                "setExpandedOffset":160}""")
            browser.show(activity.supportFragmentManager, "explicit-offset")
            chrome = browser.CustomWebChromeClient()
        }
        val initial = awaitGeometry { it.height == 480 && !it.fitToContents }
        assertEquals(initial.toString(), 160, initial.top)
        applyConfig("""{"maxHeight":240}""")
        val limited = awaitGeometry { it.height == 240 }
        assertEquals(limited.toString(), 160, limited.top)

        scenario!!.onActivity { activity ->
            chrome.onShowCustomView(View(activity), object : WebChromeClient.CustomViewCallback {
                override fun onCustomViewHidden() = Unit
            })
        }
        val full = awaitGeometry { it.height == it.parentHeight }
        assertTrue(full.toString(), abs(full.top) <= 2 && abs(full.bottomGap) <= 2)
        scenario!!.onActivity { chrome.onHideCustomView() }
        val restored = awaitGeometry { it.height == 240 }
        assertEquals(restored.toString(), 160, restored.top)
    }

    @Test
    fun fullScreenDefersHeightUpdatesAndRestoresBottomAnchor() {
        lateinit var chrome: BottomWebViewDialog.CustomWebChromeClient
        scenario!!.onActivity { activity ->
            val browser = newDialog(config = """{"heightPercentage":0.75,"setFitToContents":false}""")
            browser.show(activity.supportFragmentManager, "full-screen-height")
            chrome = browser.CustomWebChromeClient()
        }
        val initial = awaitGeometry { !it.fitToContents && it.height in 1 until it.parentHeight }
        scenario!!.onActivity { activity ->
            chrome.onShowCustomView(View(activity), object : WebChromeClient.CustomViewCallback {
                override fun onCustomViewHidden() = Unit
            })
        }
        val full = awaitGeometry { it.height == it.parentHeight }
        assertTrue(full.toString(), abs(full.top) <= 2 && abs(full.bottomGap) <= 2)
        applyConfig("""{"heightPercentage":0.5,"setFitToContents":false}""")
        assertEquals(full.height, sheetGeometry().height)
        scenario!!.onActivity { chrome.onHideCustomView() }
        val restored = awaitGeometry { it.height < initial.height && !it.fitToContents }
        assertTrue(restored.toString(), abs(restored.bottomGap) <= 2)
        screenshot("paragraph-sheet-after-full-screen")
    }

    @Test
    fun attachedSourcesSwipeDownAfterReusingTheSameWebView() {
        // Only the dialog options from #1135 comment 5578580309 are needed;
        // local HTML keeps the native gesture and pool regression independent of login/network.
        val source1Config = """{"expandedCornersRadius":15,"backgroundDimAmount":0.7,
            "heightPercentage":0.9}"""
        val source2Config = """{"expandedCornersRadius":20,"dismissOnTouchOutside":true,
            "isDraggable":true,"shouldDimBackground":true,"backgroundDimAmount":0.5,
            "hardwareAccelerated":true,"isNestedScrollingEnabled":true,
            "isGestureInsetBottomIgnored":true,"setFitToContents":false,
            "heightPercentage":0.85,"isHideable":true}"""
        val secondSource = source.copy(bookSourceUrl = source.bookSourceUrl + "/second")
        appDb.bookSourceDao.insert(secondSource)
        val failures = mutableListOf<String>()
        var previous: PooledWebView? = null
        try {
            for ((index, entry) in listOf(source to source1Config, secondSource to source2Config,
                source to source1Config).withIndex()) {
                val label = "source-${if (index == 1) 2 else 1}-step-${index + 1}"
                lateinit var browser: BottomWebViewDialog
                lateinit var pooled: PooledWebView
                scenario!!.onActivity { activity ->
                    browser = BottomWebViewDialog(entry.first.bookSourceUrl, 0,
                        "${entry.first.bookSourceUrl}/$label",
                        """<html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                            <title>$label</title></head><body style="margin:0;background:#dbeafe">
                            <h2>$label</h2><p>Swipe down from this comment page to close it.</p>
                            </body></html>""", config = entry.second)
                    browser.show(activity.supportFragmentManager, label)
                    val field = BottomWebViewDialog::class.java.getDeclaredField("pooledWebView")
                    field.isAccessible = true
                    pooled = field.get(browser) as PooledWebView
                    previous?.let { assertSame("The regression must reuse one WebView", it, pooled) }
                }
                previous = pooled
                val geometry = awaitGeometry {
                    it.state == BottomSheetBehavior.STATE_EXPANDED && it.height > 0 &&
                        abs(it.bottomGap) <= 2
                }
                assertTrue("$label lost its bottom anchor: $geometry", abs(geometry.bottomGap) <= 2)
                assertTrue("The local comment page did not finish loading", awaitCondition {
                    pooled.realWebView.title == label && pooled.realWebView.progress == 100 &&
                        pooled.realWebView.width > 0 && !pooled.realWebView.canScrollVertically(-1)
                })
                screenshot("paragraph-$label-before-swipe")
                onView(isAssignableFrom(WebView::class.java)).inRoot(isDialog()).perform(
                    GeneralSwipeAction(Swipe.FAST, { swipePoint(it, 0.15f) },
                        { swipePoint(it, 0.90f) }, Press.FINGER))
                if (!awaitCondition(timeoutMs = 3_000) { browser.dialog?.isShowing != true }) {
                    failures += "$label did not dismiss: ${sheetGeometry()}, " +
                        "nestedScrolling=${pooled.realWebView.isNestedScrollingEnabled}"
                    screenshot("paragraph-$label-blocked")
                    // Clean up a failed stage so the source 1 -> source 2 -> source 1 sequence
                    // still records whether the same recycled view contaminates the next source.
                    scenario!!.onActivity { browser.dismiss() }
                }
                assertTrue("WebView was not returned to the pool after $label",
                    awaitCondition { !pooled.isInUse })
            }
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        } finally {
            scenario!!.onActivity {
                previous?.realWebView?.isNestedScrollingEnabled = false
            }
            appDb.bookSourceDao.delete(secondSource.bookSourceUrl)
        }
    }

    private fun swipePoint(view: View, heightFraction: Float): FloatArray {
        val visible = Rect()
        assertTrue(view.getGlobalVisibleRect(visible))
        return floatArrayOf(visible.exactCenterX(), visible.top + visible.height() * heightFraction)
    }

    private fun awaitCondition(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            var matched = false
            scenario!!.onActivity { matched = condition() }
            if (matched) return true
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        return false
    }

    private fun newDialog(page: String = "comments", config: String? = null) = BottomWebViewDialog(
        source.bookSourceUrl,
        0,
        "${source.bookSourceUrl}/$page",
        "<html><body>$page</body></html>",
        config = config,
    )

    private fun applyConfig(config: String) {
        scenario!!.onActivity { activity ->
            activity.supportFragmentManager.fragments
                .filterIsInstance<BottomWebViewDialog>()
                .single { it.dialog?.isShowing == true }
                .upConfig(config)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun sheetGeometry(): SheetGeometry {
        var geometry: SheetGeometry? = null
        scenario!!.onActivity { activity ->
            val dialog = activity.supportFragmentManager.fragments
                .filterIsInstance<BottomWebViewDialog>()
                .single { it.dialog?.isShowing == true }
            val sheet = checkNotNull(dialog.dialog?.findViewById<View>(MaterialR.id.design_bottom_sheet))
            val parent = sheet.parent as View
            val behavior = BottomSheetBehavior.from(sheet)
            geometry = SheetGeometry(
                top = sheet.top,
                bottomGap = parent.height - sheet.bottom,
                height = sheet.height,
                parentHeight = parent.height,
                fitToContents = behavior.isFitToContents,
                state = behavior.state,
            )
        }
        return checkNotNull(geometry)
    }

    private fun awaitGeometry(condition: (SheetGeometry) -> Boolean): SheetGeometry {
        val deadline = SystemClock.uptimeMillis() + 5000
        var geometry: SheetGeometry
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            geometry = sheetGeometry()
            if (condition(geometry)) return geometry
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Sheet did not reach the expected geometry: $geometry")
    }

    private data class SheetGeometry(
        val top: Int,
        val bottomGap: Int,
        val height: Int,
        val parentHeight: Int,
        val fitToContents: Boolean,
        val state: Int,
    )

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(instrumentation.targetContext.getExternalFilesDir("ui-regression"), "$name.png")
                .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun visibleDialogs(manager: FragmentManager) = manager.fragments.count {
        it is BottomWebViewDialog && it.dialog?.isShowing == true
    }
}
