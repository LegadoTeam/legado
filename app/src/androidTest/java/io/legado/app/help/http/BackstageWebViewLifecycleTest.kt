package io.legado.app.help.http

import android.os.Handler
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.appDb
import io.legado.app.help.CacheManager
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebViewPool
import io.legado.app.utils.runOnUI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class BackstageWebViewLifecycleTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun cancelledStartupDoesNotAcquireBeforeOrAfterLoadWasQueued() = runBlocking {
        for (cancelBeforeStart in listOf(true, false)) {
            val request = BackstageWebView(
                url = OLD_URL, html = OLD_HTML, javaScript = "null", timeout = TIMEOUT,
            )
            val parent = Job()
            val mainEntered = CountDownLatch(1)
            val releaseMain = CountDownLatch(1)
            val observed = CompletableDeferred<PooledWebView?>()
            try {
                runOnUI {
                    mainEntered.countDown()
                    releaseMain.await(5, TimeUnit.SECONDS)
                }
                assertTrue("Main queue gate was not reached", mainEntered.await(5, TimeUnit.SECONDS))
                if (cancelBeforeStart) parent.cancel()
                // UNDISPATCHED reaches the initial suspension even with an already cancelled job.
                val result = CoroutineScope(parent + Dispatchers.Default).async(
                    start = CoroutineStart.UNDISPATCHED,
                ) { request.getStrResponse() }
                runOnUI { observed.complete(leaseOf(request)) }
                if (!cancelBeforeStart) result.cancel()
                releaseMain.countDown()
                val acquired = withTimeout(TIMEOUT) { observed.await() }
                result.join()
                assertTrue(result.isCancelled)
                assertNull("Cancelled startup acquired a WebView; cancelled before start=$cancelBeforeStart", acquired)
            } finally {
                releaseMain.countDown()
                parent.cancel()
                withContext(Dispatchers.Main) { disposeForTest(request) }
            }
        }
    }

    @Test
    fun cancelledHtmlCallbacksCannotChangeTheNextBorrowersPage() = runBlocking {
        assertCancelledCallbacksAreIsolated(sniffer = false)
    }

    @Test
    fun cancelledSnifferCallbacksCannotChangeTheNextBorrowersPage() = runBlocking {
        assertCancelledCallbacksAreIsolated(sniffer = true)
    }

    @Test
    fun nullResultsRetryUntilARealJavascriptValueIsReturned() = runBlocking {
        val request = BackstageWebView(
            url = OLD_URL,
            html = OLD_HTML,
            javaScript = """
                window.attempts = (window.attempts || 0) + 1;
                window.attempts < 3 ? null : 'attempts=' + window.attempts;
            """.trimIndent(),
            timeout = TIMEOUT,
        )
        try {
            assertEquals("attempts=3", request.getStrResponse().body)
            awaitMainCondition("Completed HTML request retained its lease") { leaseOf(request) == null }
        } finally {
            withContext(Dispatchers.Main) { disposeForTest(request) }
        }
    }

    @Test
    fun activeSnifferScriptsStillReturnResourceAndOverrideUrls() = runBlocking {
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = newFixedLengthResponse(
                Response.Status.OK, "application/javascript", "window.resourceLoaded = true;",
            )
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.listeningPort}"
            for (interceptNavigation in listOf(false, true)) {
                val target = "$base/${if (interceptNavigation) "redirect" else "resource.js"}"
                val request = BackstageWebView(
                    url = "$base/page",
                    html = OLD_HTML,
                    javaScript = if (interceptNavigation) {
                        "window.location.href = '$target'; void(0);"
                    } else {
                        "var script = document.createElement('script'); script.src = '$target'; " +
                            "document.head.appendChild(script); void(0);"
                    },
                    sourceRegex = if (interceptNavigation) null else Regex.escape(target),
                    overrideUrlRegex = if (interceptNavigation) Regex.escape(target) else null,
                    timeout = TIMEOUT,
                )
                try {
                    assertEquals(target, request.getStrResponse().body)
                    awaitMainCondition("Completed sniffer request retained its lease") { leaseOf(request) == null }
                } finally {
                    withContext(Dispatchers.Main) { disposeForTest(request) }
                }
            }
        } finally {
            server.stop()
        }
    }

    private suspend fun assertCancelledCallbacksAreIsolated(sniffer: Boolean) {
        val savedDiskResult = appDb.cacheDao.get("webview_result")
        val savedMemoryResult = CacheManager.getFromMemory("webview_result")
        val request = BackstageWebView(
            url = OLD_URL,
            html = OLD_HTML,
            javaScript = STALE_SCRIPT,
            sourceRegex = if (sniffer) "(?!)" else null,
            delayTime = 30_000,
            timeout = 60_000,
            result = if (sniffer) null else "obsolete-result",
            isRule = !sniffer,
        )
        val parent = Job()
        val result = CoroutineScope(parent + Dispatchers.Default).async { request.getStrResponse() }
        var borrowed: PooledWebView? = null
        try {
            val original = awaitLease(request)
            awaitMainCondition("Original page did not load") {
                original.realWebView.title == "obsolete" && original.realWebView.progress == 100
            }
            val (oldClient, oldTask) = withContext(Dispatchers.Main) {
                val client = original.realWebView.webViewClient
                client.onPageFinished(original.realWebView, OLD_URL)
                val task = if (sniffer) {
                    // Sniffer does not retain its task; instantiate its real Runnable with the same owner.
                    val type = client.javaClass.declaredClasses.single { it.simpleName == "LoadJsRunnable" }
                    type.declaredConstructors.single().apply { isAccessible = true }
                        .newInstance(client, original.realWebView, STALE_SCRIPT) as Runnable
                } else {
                    client.javaClass.getDeclaredField("runnable").apply { isAccessible = true }
                        .get(client) as Runnable
                }
                handlerOf(request).postDelayed(task, 30_000)
                client to task
            }
            withContext(Dispatchers.Main) { oldTask.run() }
            assertTrue("The retained native script must work while its request owns the view",
                evaluate(original.realWebView, "window.staleRuns").toInt() >= 1)
            result.cancelAndJoin()
            withContext(Dispatchers.Main) { assertNull(leaseOf(request)) }
            awaitMainCondition("Cancelled WebView was not recycled") { !original.isInUse }
            val next = withContext(Dispatchers.Main) {
                WebViewPool.acquire(context).also {
                    borrowed = it
                    assertSame("The regression must reuse the cancelled request's native WebView", original, it)
                    it.realWebView.onResume()
                }
            }
            loadBorrowerPage(next.realWebView)
            assertEquals("[0,\"borrower\"]", evaluate(next.realWebView, "[window.staleRuns, window.result]"))
            val cleared = withContext(Dispatchers.Main) {
                val wasCleared = !handlerOf(request).hasCallbacks(oldTask)
                // Dispatch retained callbacks after release, even if destroy removed their queue entries.
                oldClient.onPageFinished(next.realWebView, OLD_URL)
                oldTask.run()
                wasCleared
            }
            assertEquals(
                "An obsolete ${if (sniffer) "sniffer" else "HTML"} task changed the next borrower's JavaScript state",
                "[0,\"borrower\"]",
                evaluate(next.realWebView, "[window.staleRuns, window.result]"),
            )
            assertTrue("Cancellation left an old script queued", cleared)
            assertTrue("The next borrower lost its lease", withContext(Dispatchers.Main) { next.isInUse })
            assertTrue(result.isCancelled)
        } finally {
            result.cancelAndJoin()
            parent.cancel()
            withContext(Dispatchers.Main) {
                disposeForTest(request)
                borrowed?.let { WebViewPool.release(it) }
            }
            if (savedDiskResult == null) appDb.cacheDao.delete("webview_result")
            else appDb.cacheDao.insert(savedDiskResult)
            if (savedMemoryResult == null) CacheManager.deleteMemory("webview_result")
            else CacheManager.putMemory("webview_result", savedMemoryResult)
        }
    }

    private suspend fun loadBorrowerPage(webView: WebView) {
        val loaded = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (view.title == "borrower") loaded.complete(Unit)
                }
            }
            webView.loadDataWithBaseURL(
                "https://backstage-lifecycle.invalid/borrower",
                """<html><head><title>borrower</title></head><body><script>
                    window.staleRuns = 0; window.result = 'borrower';
                    window.${WebJsExtensions.nameCache} = {getFromMemory: function() { return 'obsolete-result'; }};
                    </script></body></html>""".trimIndent(),
                "text/html", "UTF-8", null,
            )
        }
        withTimeout(TIMEOUT) { loaded.await() }
    }

    private suspend fun evaluate(webView: WebView, script: String): String = withTimeout(TIMEOUT) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                webView.evaluateJavascript(script) {
                    if (continuation.isActive) continuation.resume(it)
                }
            }
        }
    }

    private suspend fun awaitLease(request: BackstageWebView): PooledWebView = withTimeout(TIMEOUT) {
        var lease: PooledWebView? = null
        while (lease == null) {
            lease = withContext(Dispatchers.Main) { leaseOf(request) }
            if (lease == null) delay(10)
        }
        checkNotNull(lease)
    }

    private suspend fun awaitMainCondition(message: String, condition: () -> Boolean) {
        val matched = withTimeoutOrNull(TIMEOUT) {
            while (!withContext(Dispatchers.Main) { condition() }) delay(10)
            true
        }
        assertTrue(message, matched == true)
    }

    private fun leaseOf(request: BackstageWebView): PooledWebView? =
        BackstageWebView::class.java.getDeclaredField("pooledWebView").apply { isAccessible = true }
            .get(request) as PooledWebView?

    private fun handlerOf(request: BackstageWebView): Handler =
        BackstageWebView::class.java.getDeclaredField("mHandler").apply { isAccessible = true }
            .get(request) as Handler

    private fun disposeForTest(request: BackstageWebView) {
        // Also clean up on the unfixed negative-control baseline, where destroy leaves queued scripts.
        handlerOf(request).removeCallbacksAndMessages(null)
        BackstageWebView::class.java.getDeclaredMethod("destroy").apply { isAccessible = true }
            .invoke(request)
    }

    companion object {
        private const val TIMEOUT = 10_000L
        private const val OLD_URL = "https://backstage-lifecycle.invalid/obsolete"
        private const val OLD_HTML = "<html><head><title>obsolete</title></head><body>Original page</body></html>"
        private const val STALE_SCRIPT = "window.staleRuns = (window.staleRuns || 0) + 1; void(0);"
    }
}
