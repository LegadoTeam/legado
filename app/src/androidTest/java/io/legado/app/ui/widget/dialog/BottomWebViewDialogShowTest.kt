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
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.R as MaterialR
import fi.iki.elonen.NanoHTTPD
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
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun edgeBackExitsFullscreenThenHistoryThenOnlyTheTopBrowser() {
        val overlay = shell("cmd overlay list --user current").lineSequence()
            .map(String::trim).first { it.startsWith("[x] com.android.internal.systemui.navbar.") }
            .removePrefix("[x] ")
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = newFixedLengthResponse(
                Response.Status.OK, "text/html",
                "<html><head><title>Second</title></head><body>${session.uri}</body></html>"
            )
        }
        try {
            server.start()
            writeBackEvidence("paragraph-back-system-before-setup", systemBackState())
            shell("cmd overlay enable-exclusive --user current --category com.android.internal.systemui.navbar.gestural")
            assertTrue("System gesture navigation must actually be enabled", awaitCondition {
                val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
                val mode = resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
                mode != 0 && resources.getInteger(mode) == 2
            })
            awaitSystemBackGestures()
            for (outside in listOf(false, true)) {
                val firstUrl = "http://127.0.0.1:${server.listeningPort}/back-$outside"
                val secondUrl = "$firstUrl/second"
                lateinit var lower: BottomWebViewDialog
                lateinit var browser: BottomWebViewDialog
                lateinit var web: WebView
                scenario!!.onActivity { activity ->
                    lower = newDialog("lower-$outside", """{"heightPercentage":0.5}""")
                    lower.show(activity.supportFragmentManager, "lower")
                    browser = BottomWebViewDialog(source.bookSourceUrl, 0, firstUrl,
                        "<html><head><title>First</title></head><body>First</body></html>",
                        config = """{"heightPercentage":0.6,
                            "dismissOnTouchOutside":$outside,"isHideable":true}""")
                    browser.show(activity.supportFragmentManager, "back")
                    web = (browser.requireView().findViewById<View>(io.legado.app.R.id.web_view_container)
                        as android.view.ViewGroup).getChildAt(0) as WebView
                }
                assertTrue("Browser initial page must be ready", awaitCondition {
                    web.url == firstUrl && web.title == "First" &&
                        web.progress == 100 && web.copyBackForwardList().size == 1 &&
                        browser.dialog?.window?.decorView?.hasWindowFocus() == true
                })
                scenario!!.onActivity {
                    // Navigate to a second document instead of assuming pushState updates the
                    // virtual URL of the initial loadDataWithBaseURL entry on every WebView.
                    web.loadUrl(secondUrl)
                }
                var historyState = ""
                val historyReady = awaitCondition {
                    val history = web.copyBackForwardList()
                    historyState = "url=${web.url}, title=${web.title}, progress=${web.progress}, " +
                        "size=${history.size}, index=${history.currentIndex}"
                    web.canGoBack() && web.url == secondUrl && web.title == "Second" &&
                        web.progress == 100 && history.size == 2 && history.currentIndex == 1
                }
                assertTrue("The fixture must create real WebView history: $historyState", historyReady)
                var hidden = 0
                scenario!!.onActivity { activity ->
                    val chrome = browser.CustomWebChromeClient()
                    chrome.onShowCustomView(View(activity), object : WebChromeClient.CustomViewCallback {
                        override fun onCustomViewHidden() {
                            hidden++
                            chrome.onHideCustomView()
                        }
                    })
                }
                recordBackState("paragraph-back-before-fullscreen-$outside", browser, web, hidden)
                edgeBack(browser)
                val exitedFullscreen = awaitCondition {
                    hidden == 1 && browser.dialog?.isShowing == true && web.canGoBack()
                }
                val fullscreenState = recordBackState(
                    "paragraph-back-after-fullscreen-$outside", browser, web, hidden)
                assertTrue("Back must exit fullscreen without dismissing or navigating: $fullscreenState",
                    exitedFullscreen)
                screenshot("paragraph-back-fullscreen-$outside")
                edgeBack(browser)
                assertTrue("Back must consume the real web history before dismissing", awaitCondition {
                    browser.dialog?.isShowing == true && !web.canGoBack() &&
                        web.url == firstUrl && web.title == "First" &&
                        web.copyBackForwardList().currentIndex == 0
                })
                val historyDrawn = CountDownLatch(1)
                scenario!!.onActivity {
                    web.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            web.postOnAnimation { web.postOnAnimation { historyDrawn.countDown() } }
                        }
                    })
                }
                assertTrue("The restored history page must reach the compositor before capture",
                    historyDrawn.await(5, TimeUnit.SECONDS))
                screenshot("paragraph-back-history-$outside")
                edgeBack(browser)
                assertTrue("Back must close only the top browser", awaitCondition {
                    browser.dialog?.isShowing != true && lower.dialog?.isShowing == true
                })
                scenario!!.onActivity {
                    assertFalse("A dialog back gesture must not finish the host", it.isFinishing)
                    lower.dismiss()
                }
            }
        } finally {
            server.stop()
            shell("cmd overlay enable-exclusive --user current --category $overlay")
        }
    }

    @Test
    fun outsideTouchToggleDoesNotDisableKeyBackOrSwipeDismissal() {
        lateinit var browser: BottomWebViewDialog
        scenario!!.onActivity { activity ->
            browser = newDialog(config = """{"heightPercentage":0.5,
                "dismissOnTouchOutside":false,"isHideable":true}""")
            browser.show(activity.supportFragmentManager, "outside-off")
        }
        awaitGeometry { it.height > 0 && it.top > 0 && it.state == BottomSheetBehavior.STATE_EXPANDED }
        assertTrue(awaitCondition { browser.dialog?.window?.decorView?.hasWindowFocus() == true })
        tapOutside(browser)
        scenario!!.onActivity {
            assertTrue(browser.dialog?.isShowing == true)
            assertTrue("Outside-touch setting must not disable downward dismissal",
                BottomSheetBehavior.from(checkNotNull(browser.requireDialog()
                    .findViewById<View>(MaterialR.id.design_bottom_sheet))).isHideable)
        }
        pressBack()
        assertTrue("Hardware back must close even when outside touch is disabled",
            awaitCondition { browser.dialog?.isShowing != true })
        scenario!!.onActivity { activity ->
            browser = newDialog(config = """{"heightPercentage":0.5,
                "dismissOnTouchOutside":false,"isHideable":true}""")
            browser.show(activity.supportFragmentManager, "outside-off-swipe")
        }
        awaitGeometry { it.height > 0 && it.top > 0 && it.state == BottomSheetBehavior.STATE_EXPANDED }
        assertTrue(awaitCondition { browser.dialog?.window?.decorView?.hasWindowFocus() == true })
        onView(isAssignableFrom(WebView::class.java)).inRoot(isDialog()).perform(
            GeneralSwipeAction(Swipe.FAST, { swipePoint(it, 0.15f) },
                { swipePoint(it, 0.90f) }, Press.FINGER))
        assertTrue("Outside-touch setting must preserve real downward swipe dismissal",
            awaitCondition { browser.dialog?.isShowing != true })
        scenario!!.onActivity { activity ->
            browser = newDialog(config = """{"heightPercentage":0.5,"dismissOnTouchOutside":false}""")
            browser.show(activity.supportFragmentManager, "outside-toggle")
            browser.upConfig("""{"dismissOnTouchOutside":true}""")
        }
        awaitGeometry { it.height > 0 && it.top > 0 && it.state == BottomSheetBehavior.STATE_EXPANDED }
        assertTrue(awaitCondition { browser.dialog?.window?.decorView?.hasWindowFocus() == true })
        tapOutside(browser)
        assertTrue("Enabling outside touch must still dismiss the browser",
            awaitCondition { browser.dialog?.isShowing != true })
        scenario!!.onActivity { assertFalse(it.isFinishing) }
    }

    private fun edgeBack(browser: BottomWebViewDialog) {
        var width = 0
        var y = 0
        assertTrue(awaitCondition { browser.dialog?.window?.decorView?.hasWindowFocus() == true })
        scenario!!.onActivity {
            val decor = browser.requireDialog().window!!.decorView
            width = decor.width
            y = decor.height * 2 / 3
        }
        // Inject a touchscreen gesture from the system edge, never a KEYCODE_BACK surrogate.
        shell("input touchscreen swipe 1 $y ${width * 3 / 4} $y 350")
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun systemBackState(): String =
        "qemu.hw.mainkeys=${shell("getprop qemu.hw.mainkeys").trim()}\n" +
        "user_setup_complete=${shell("settings --user current get secure user_setup_complete").trim()}\n" +
        shell("dumpsys activity service com.android.systemui/.SystemUIService dumpables")

    private fun awaitSystemBackGestures() {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var state: String
        do {
            state = systemBackState()
            val ready = state.split("EdgeBackGestureHandler:").drop(1).any { section ->
                val handler = section.lineSequence().take(12).joinToString("\n")
                handler.contains("mIsEnabled=true") && handler.contains("mIsAttached=true") &&
                    handler.contains("mIsBackGestureAllowed=true")
            }
            if (ready) {
                writeBackEvidence("paragraph-back-system-ready", state)
                return
            }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        writeBackEvidence("paragraph-back-system-not-ready", state)
        throw AssertionError("SystemUI must enable and allow actual edge back gestures; see system-not-ready.txt")
    }

    private fun recordBackState(name: String, browser: BottomWebViewDialog, web: WebView,
                                hidden: Int): String {
        var state = ""
        scenario!!.onActivity { activity ->
            val history = web.copyBackForwardList()
            val sheet = browser.dialog?.findViewById<View>(MaterialR.id.design_bottom_sheet)
            state = "hidden=$hidden, showing=${browser.dialog?.isShowing}, " +
                "focus=${browser.dialog?.window?.decorView?.hasWindowFocus()}, " +
                "fullscreenChildren=${(browser.view?.findViewById<View>(io.legado.app.R.id.custom_web_view)
                    as? android.view.ViewGroup)?.childCount}, " +
                "url=${web.url}, canGoBack=${web.canGoBack()}, index=${history.currentIndex}, " +
                "sheetState=${sheet?.let { BottomSheetBehavior.from(it).state }}, " +
                "hostFinishing=${activity.isFinishing}"
        }
        writeBackEvidence(name, "$state\n\n${systemBackState()}\n\n${shell("dumpsys window windows")}")
        screenshot(name)
        return state
    }

    private fun writeBackEvidence(name: String, state: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir("ui-regression"), "$name.txt").writeText(state)
    }

    private fun tapOutside(browser: BottomWebViewDialog) {
        var x = 0
        var y = 0
        scenario!!.onActivity {
            val sheet = checkNotNull(browser.requireDialog().findViewById<View>(MaterialR.id.design_bottom_sheet))
            val location = IntArray(2)
            sheet.getLocationOnScreen(location)
            x = sheet.width / 2
            y = location[1] / 2
            assertTrue("The fixture needs an exposed outside-touch area", y > 0)
        }
        shell("input touchscreen tap $x $y")
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
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
