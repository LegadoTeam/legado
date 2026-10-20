package io.legado.app.help.http

import android.os.SystemClock
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebViewDiagnostics
import io.legado.app.help.webView.WebViewDiagnosticsRule
import io.legado.app.help.webView.WebViewPool
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.widget.dialog.BottomWebViewDialog
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Each method requires its own instrumentation process; only idle duration differs. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class WebViewPoolIdleAgingTest {
    @get:Rule
    val diagnostics = WebViewDiagnosticsRule(watchFromStart = false)

    private val source = BookSource(
        bookSourceUrl = "https://example.invalid/webview-idle-aging",
        bookSourceName = "WebView idle aging fixture",
        enabled = false,
        enabledExplore = false,
    )
    private var previousSource: BookSource? = null
    private var sourceInserted = false
    private var scenario: ActivityScenario<AboutActivity>? = null

    @Before
    fun setUp() {
        previousSource = appDb.bookSourceDao.getBookSource(source.bookSourceUrl)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue("Run each aging method in a fresh instrumentation process", idlePool().isEmpty())
            assertTrue("The fresh process must have no borrowers", inUsePool().isEmpty())
        }
        appDb.bookSourceDao.insert(source)
        sourceInserted = true
        scenario = ActivityScenario.launch(AboutActivity::class.java)
    }

    @After
    fun tearDown() {
        try {
            scenario?.close()
        } finally {
            if (sourceInserted) {
                previousSource?.let { appDb.bookSourceDao.insert(it) }
                    ?: appDb.bookSourceDao.delete(source.bookSourceUrl)
            }
        }
    }

    @Test
    fun staticBackstagePageAfterThirtySecondsIdle() = runIdleProbe(30_000L)

    @Test
    fun staticBackstagePageAfterSixMinutesIdle() = runIdleProbe(360_000L)

    private fun runIdleProbe(idleMillis: Long) = runBlocking {
        val pageUrl = "${source.bookSourceUrl}/page"
        val dialog = BottomWebViewDialog(source.bookSourceUrl, 0, pageUrl,
            "<html><head><title>Idle fixture</title></head><body>Idle fixture</body></html>")
        lateinit var target: PooledWebView
        scenario!!.onActivity {
            dialog.show(it.supportFragmentManager, "idle-aging")
            target = BottomWebViewDialog::class.java.getDeclaredField("pooledWebView")
                .apply { isAccessible = true }.get(dialog) as PooledWebView
            val container = dialog.requireView().findViewById<ViewGroup>(R.id.web_view_container)
            assertSame(target.realWebView, container.getChildAt(0))
        }
        awaitCondition("The attached fixture did not finish its real HTML") {
            target.realWebView.isAttachedToWindow && target.realWebView.isShown &&
                target.realWebView.title == "Idle fixture" && target.realWebView.progress == 100
        }
        scenario!!.onActivity { dialog.dismiss() }
        awaitCondition("The dismissed fixture did not finish blank recycling") {
            !target.isInUse && idlePool().size == 1 && inUsePool().isEmpty()
        }
        withContext(Dispatchers.Main) { assertIdleTarget(target) }
        val idleStarted = SystemClock.uptimeMillis()
        WebViewDiagnostics.record("idle-aging") { "idle started requestedMs=$idleMillis id=${target.id}" }
        delay(idleMillis)
        withContext(Dispatchers.Main) {
            assertIdleTarget(target)
            WebViewDiagnostics.record("idle-aging") {
                "idle ended requestedMs=$idleMillis elapsedMs=${SystemClock.uptimeMillis() - idleStarted} " +
                    "sinceRecycleMs=${System.currentTimeMillis() - target.lastUseTime} id=${target.id}"
            }
        }

        val request = BackstageWebView(
            url = pageUrl, html = "<p>Background</p>",
            javaScript = "document.querySelector('p').textContent",
            tag = source.bookSourceUrl, isRule = true, timeout = REQUEST_TIMEOUT,
        )
        diagnostics.watch("backstage")
        try {
            val response = withContext(Dispatchers.Main) {
                // Main/UNDISPATCHED reaches the normal load synchronously, before its first callback.
                val pending = async(start = CoroutineStart.UNDISPATCHED) { request.getStrResponse() }
                val actual = BackstageWebView::class.java.getDeclaredField("pooledWebView")
                    .apply { isAccessible = true }.get(request)
                assertSame("Backstage must borrow the aged native instance", target, actual)
                assertEquals(1L, target.recycleGeneration)
                pending.await()
            }
            assertEquals("Background", response.body)
            awaitCondition("The Backstage request did not return its lease") { !target.isInUse }
        } finally {
            diagnostics.stopWatching()
            withContext(NonCancellable + Dispatchers.Main) {
                BackstageWebView::class.java.getDeclaredMethod("destroy")
                    .apply { isAccessible = true }.invoke(request)
            }
        }
    }

    private fun assertIdleTarget(target: PooledWebView) {
        assertEquals(1, idlePool().size)
        assertTrue(inUsePool().isEmpty())
        assertSame(target, idlePool().single())
        assertFalse(target.isInUse)
        assertEquals(1L, target.recycleGeneration)
        assertEquals("${WebViewPool.BLANK_HTML}?legado-recycle=1", target.realWebView.url)
        assertEquals(100, target.realWebView.progress)
        assertFalse(target.realWebView.isAttachedToWindow)
    }

    private suspend fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + REQUEST_TIMEOUT
        while (SystemClock.uptimeMillis() < deadline) {
            if (withContext(Dispatchers.Main) { condition() }) return
            delay(10)
        }
        throw AssertionError(message)
    }

    private fun idlePool(): List<*> = (WebViewPool::class.java.getDeclaredField("idlePool")
        .apply { isAccessible = true }.get(WebViewPool) as java.util.Stack<*>).toList()

    private fun inUsePool(): Map<*, *> = WebViewPool::class.java.getDeclaredField("inUsePool")
        .apply { isAccessible = true }.get(WebViewPool) as Map<*, *>

    companion object {
        private const val REQUEST_TIMEOUT = 10_000L
    }
}
