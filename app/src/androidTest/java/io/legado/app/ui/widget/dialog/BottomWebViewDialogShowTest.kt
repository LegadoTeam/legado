package io.legado.app.ui.widget.dialog

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.webkit.WebChromeClient
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.R as MaterialR
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.ui.about.AboutActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
