package io.legado.app.ui.browser

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class WebViewFullscreenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<WebViewActivity>

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(Intent(
            instrumentation.targetContext, WebViewActivity::class.java
        ).apply {
            putExtra("url", "https://fullscreen.invalid/")
            putExtra("html", """
                <html><head><title>Fullscreen fixture</title>
                <meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0"><div id="viewport">
                <button id="enter" style="width:200px;height:80px"
                    onclick="document.getElementById('viewport').requestFullscreen()">Fullscreen</button>
                <canvas width="320" height="180"></canvas></div></body></html>
            """.trimIndent())
        })
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (evaluate("String(screen.orientation.lock).includes('lockOrientation')") == "true") {
                return
            }
            SystemClock.sleep(50)
        }
        error("The loaded document did not receive the orientation bridge")
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
    }

    @Test
    fun canvasPlayerFullscreenButtonRotatesAndBackRestoresPortrait() {
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        val button = JSONObject(evaluate("""
            (() => {
                const r = document.getElementById('enter').getBoundingClientRect();
                return {x:r.x+r.width/2, y:r.y+r.height/2, width:innerWidth};
            })()
        """.trimIndent()))
        // A real input event is required for requestFullscreen's user-activation check.
        onView(isAssignableFrom(WebView::class.java)).perform(GeneralClickAction(
            Tap.SINGLE,
            { view ->
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                val scale = view.width / button.getDouble("width")
                floatArrayOf(
                    (location[0] + button.getDouble("x") * scale).toFloat(),
                    (location[1] + button.getDouble("y") * scale).toFloat()
                )
            },
            Press.FINGER
        ))
        val deadline = SystemClock.uptimeMillis() + 5_000
        var fullscreen = false
        while (!fullscreen && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity {
                fullscreen = it.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE &&
                    it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            if (!fullscreen) SystemClock.sleep(50)
        }
        assertTrue("The canvas player's real fullscreen request must select landscape", fullscreen)
        screenshot("webview-fullscreen-landscape")
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        val restoreDeadline = SystemClock.uptimeMillis() + 5_000
        var restored = false
        while (!restored && SystemClock.uptimeMillis() < restoreDeadline) {
            scenario.onActivity {
                restored = it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            }
            if (!restored) SystemClock.sleep(50)
        }
        assertTrue("Exiting fullscreen must restore the portrait layout", restored)
        scenario.onActivity {
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, it.requestedOrientation)
        }
        screenshot("webview-fullscreen-restored")
    }

    @Test
    fun fullscreenDefaultsToLandscapeAndRestoresOriginalDirection() {
        val hidden = AtomicInteger()
        scenario.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            val chrome = activity.CustomWebChromeClient()
            chrome.onShowCustomView(View(activity), callback(hidden))
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)

            // A repeated show must neither replace the view nor overwrite the saved direction.
            val duplicate = AtomicInteger()
            chrome.onShowCustomView(View(activity), callback(duplicate))
            assertEquals(1, duplicate.get())
            assertEquals(1, activity.findViewById<android.widget.FrameLayout>(R.id.custom_web_view).childCount)

            chrome.onHideCustomView()
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, activity.requestedOrientation)
            assertEquals(1, hidden.get())
            chrome.onHideCustomView()
            assertEquals(1, hidden.get())
        }
    }

    @Test
    fun lockBeforeFullscreenIsQueuedWithoutRotatingTheBrowser() {
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        // This goes through the real WebView JavascriptInterface thread before the show callback.
        evaluate("screen.orientation.lock('landscape-secondary'); true")
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
            val chrome = activity.CustomWebChromeClient()
            chrome.onShowCustomView(View(activity), callback(AtomicInteger()))
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, activity.requestedOrientation)
            chrome.onHideCustomView()
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
        }
    }

    @Test
    fun pageCanRequestPortraitAndUnlockRestoresTheDirectionBeforeFullscreen() {
        lateinit var chrome: WebViewActivity.CustomWebChromeClient
        scenario.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            chrome = activity.CustomWebChromeClient()
            chrome.onShowCustomView(View(activity), callback(AtomicInteger()))
        }
        evaluate("screen.orientation.lock('portrait'); true")
        instrumentation.waitForIdleSync()
        scenario.onActivity {
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, it.requestedOrientation)
        }
        evaluate("screen.orientation.unlock(); true")
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, activity.requestedOrientation)
            chrome.onHideCustomView()
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, activity.requestedOrientation)
        }
    }

    @Test
    fun backExitsFullscreenAndReentrantHideOnlyNotifiesOnce() {
        val hidden = AtomicInteger()
        scenario.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            val chrome = activity.CustomWebChromeClient()
            chrome.onShowCustomView(View(activity), object : WebChromeClient.CustomViewCallback {
                override fun onCustomViewHidden() {
                    hidden.incrementAndGet()
                    chrome.onHideCustomView()
                }
            })
            activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
            assertEquals(1, hidden.get())
            assertEquals(0, activity.findViewById<android.widget.FrameLayout>(R.id.custom_web_view).childCount)
        }
    }

    private fun callback(hidden: AtomicInteger) = object : WebChromeClient.CustomViewCallback {
        override fun onCustomViewHidden() {
            hidden.incrementAndGet()
        }
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = instrumentation.targetContext.getExternalFilesDir("ui-regression")
            File(directory, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun evaluate(script: String): String {
        val complete = CountDownLatch(1)
        val result = AtomicReference<String>()
        scenario.onActivity { activity ->
            val container = activity.findViewById<android.widget.FrameLayout>(R.id.web_view_container)
            (container.getChildAt(0) as WebView).evaluateJavascript(script) {
                result.set(it)
                complete.countDown()
            }
        }
        assertTrue("WebView JavaScript did not finish", complete.await(5, TimeUnit.SECONDS))
        return result.get()
    }
}
