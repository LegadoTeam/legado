package io.legado.app.help.http

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AndroidRuntimeException
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.CacheManager
import io.legado.app.help.WebCacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.withSourceNavigationContext
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebViewRequestConfig
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.getInjectionString
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebJsExtensions.Companion.nameSource
import io.legado.app.help.webView.WebViewDiagnostics
import io.legado.app.help.webView.WebViewPool
import io.legado.app.help.webView.toWebViewRequestConfig
import io.legado.app.model.Debug
import io.legado.app.utils.runOnUI
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.apache.commons.text.StringEscapeUtils
import splitties.init.appCtx
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.CoroutineContext

/**
 * 后台webView
 */
class BackstageWebView(
    private val url: String? = null,
    private val html: String? = null,
    private val encode: String? = null,
    private val tag: String? = null,
    private val headerMap: HashMap<String, String>? = null,
    private val sourceRegex: String? = null,
    private val overrideUrlRegex: String? = null,
    private val javaScript: String? = null,
    private var delayTime: Long = 0,
    private val cacheFirst: Boolean = false,
    private val timeout: Long? = null,
    private val result: String? = null,
    private val isRule: Boolean = false
) {

    private val mHandler = Handler(Looper.getMainLooper())
    private var callback: Callback? = null
    private var pooledWebView: PooledWebView? = null

    private fun diagnostic(stage: () -> String) {
        if (!WebViewDiagnostics.enabled) return
        WebViewDiagnostics.record("request@${System.identityHashCode(this)}") {
            "lease=${pooledWebView?.id} ${stage()}"
        }
    }

    private fun diagnosticCallback(view: WebView?, context: CoroutineContext, stage: () -> String) {
        diagnostic {
            "${stage()} view=${view?.let { System.identityHashCode(it) }} active=${context.isActive} matchingLease=${view != null && pooledWebView?.realWebView === view}"
        }
    }

    suspend fun getStrResponse(): StrResponse = withTimeout(timeout ?: 60000L) {
        suspendCancellableCoroutine { block ->
            diagnostic { "request entered htmlLength=${html?.length} scriptLength=${javaScript?.length} isRule=$isRule" }
            block.invokeOnCancellation {
                diagnostic { "cancellation dispatched" }
                runOnUI {
                    diagnostic { "cancellation cleanup entered" }
                    destroy()
                }
            }
            diagnostic { "load queued" }
            runOnUI {
                diagnostic { "load queue entered active=${block.isActive}" }
                if (!block.isActive) return@runOnUI
                callback = object : Callback() {
                    override fun onResult(response: StrResponse) {
                        diagnostic { "result callback active=${block.isActive}" }
                        if (block.isActive) {
                            block.resume(response)
                        }
                    }

                    override fun onError(error: Throwable) {
                        diagnostic { "error callback active=${block.isActive} error=${error.javaClass.simpleName}" }
                        if (block.isActive)
                            block.resumeWithException(error)
                    }
                }
                if (javaScript == null && delayTime == 0L) {
                    delayTime = 900L
                }
                try {
                    load(block.context)
                    diagnostic { "load returned" }
                } catch (error: Throwable) {
                    callback?.onError(error)
                    destroy()
                }
            }
        }
    }

    private fun getEncoding(): String {
        return encode ?: "utf-8"
    }

    @Throws(AndroidRuntimeException::class)
    private fun load(navigationContext: CoroutineContext) {
        val requestConfig = headerMap.toWebViewRequestConfig(AppConfig.userAgent)
        diagnostic { "createWebView entered" }
        val webView = createWebView(requestConfig, navigationContext)
        diagnostic { "createWebView returned" }
        try {
            when {
                !html.isNullOrEmpty() -> {
                    if (isRule) {
                        diagnostic { "bridge setup entered" }
                        webView.addJavascriptInterface(WebCacheManager, nameCache)
                        tag?.let { key ->
                           diagnostic { "source lookup entered" }
                           appDb.bookSourceDao.getBookSource(key)?.let {
                               diagnostic { "source lookup returned; bridge binding entered" }
                               val source = (it as BaseSource).withSourceNavigationContext(navigationContext)
                               webView.addJavascriptInterface(source, nameSource)
                               val webJsExtensions = WebJsExtensions(it, null, webView,
                                   navigationContext = navigationContext)
                               webView.addJavascriptInterface(webJsExtensions, nameJava)
                            }
                        }
                        diagnostic { "bridge setup returned" }
                    }
                    result?.let {
                        diagnostic { "result cache write entered" }
                        CacheManager.put("webview_result", it)
                        diagnostic { "result cache write returned" }
                    }
                    diagnostic { "loadDataWithBaseURL entered" }
                    webView.loadDataWithBaseURL(url, html, "text/html", getEncoding(), url)
                    diagnostic { "loadDataWithBaseURL returned" }
                }

                else -> if (requestConfig.additionalHeaders.isEmpty()) {
                    diagnostic { "loadUrl entered headers=false" }
                    webView.loadUrl(url!!)
                    diagnostic { "loadUrl returned" }
                } else {
                    diagnostic { "loadUrl entered headers=true" }
                    webView.loadUrl(url!!, requestConfig.additionalHeaders)
                    diagnostic { "loadUrl returned" }
                }
            }
        } catch (e: Exception) {
            callback?.onError(e)
            destroy()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(
        requestConfig: WebViewRequestConfig,
        navigationContext: CoroutineContext
    ): WebView {
        diagnostic { "acquire entered" }
        val pooledWebView = WebViewPool.acquire(appCtx)
        this.pooledWebView = pooledWebView
        diagnostic { "acquire returned generation=${pooledWebView.recycleGeneration}" }
        val webView = pooledWebView.realWebView
        diagnostic { "onResume entered" }
        webView.onResume() //缓存库拿的需要激活
        diagnostic { "onResume returned" }
        val settings = webView.settings
        settings.blockNetworkImage = true
        settings.userAgentString = requestConfig.userAgent
        settings.cacheMode = if(cacheFirst) WebSettings.LOAD_CACHE_ELSE_NETWORK else WebSettings.LOAD_DEFAULT
        tag?.takeIf { it.isNotBlank() }?.let { sourceTag ->
            webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    diagnostic { "console callback level=${consoleMessage.messageLevel()}" }
                    val messageLevel = consoleMessage.messageLevel().name
                    val message = consoleMessage.message()
                    Debug.log(sourceTag, "$messageLevel: $message", true)
                    return true
                }
            }
        }
        if (sourceRegex.isNullOrBlank() && overrideUrlRegex.isNullOrBlank()) {
            webView.webViewClient = HtmlWebViewClient(navigationContext)
        } else {
            webView.webViewClient = SnifferWebClient(navigationContext)
        }
        diagnostic { "clients installed sniffer=${!sourceRegex.isNullOrBlank() || !overrideUrlRegex.isNullOrBlank()}" }
        return webView
    }

    private fun destroy() {
        diagnostic { "destroy entered" }
        mHandler.removeCallbacksAndMessages(null)
        callback = null
        val released = pooledWebView
        pooledWebView = null
        released?.let { WebViewPool.release(it) }
        diagnostic { "destroy returned released=${released?.id}" }
    }

    private fun ownsWebView(view: WebView, navigationContext: CoroutineContext): Boolean =
        navigationContext.isActive && pooledWebView?.realWebView === view

    private fun getJs(): String {
        javaScript?.let {
            if (it.isNotEmpty()) {
                return it
            }
        }
        return JS
    }

    private fun setCookie(url: String) {
        tag?.let {
            Coroutine.async(executeContext = IO) {
                val cookie = CookieManager.getInstance().getCookie(url)
                CookieStore.setCookie(it, cookie)
            }
        }
    }

    private inner class HtmlWebViewClient(
        private val navigationContext: CoroutineContext
    ) : WebViewClient() {

        private var runnable: EvalJsRunnable? = null
        private var isRedirect = false

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            diagnosticCallback(view, navigationContext) { "html pageStarted urlHash=${url?.hashCode()}" }
            super.onPageStarted(view, url, favicon)
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            diagnosticCallback(view, navigationContext) { "html pageCommit urlHash=${url?.hashCode()}" }
            super.onPageCommitVisible(view, url)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            diagnosticCallback(view, navigationContext) { "html pageError code=${error.errorCode} mainFrame=${request.isForMainFrame}" }
            super.onReceivedError(view, request, error)
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            if (!ownsWebView(view, navigationContext)) return true
            isRedirect = isRedirect || if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                request.isRedirect
            } else {
                request.url.toString() != view.url
            }
            return super.shouldOverrideUrlLoading(view, request)
        }

        override fun onPageFinished(view: WebView, url: String) {
            diagnosticCallback(view, navigationContext) { "html pageFinished urlHash=${url.hashCode()}" }
            if (!ownsWebView(view, navigationContext)) return
            setCookie(url)
            result?.let {
                diagnostic { "result injection entered" }
                view.evaluateJavascript("window.result = $nameCache.getFromMemory('webview_result')", null)
                diagnostic { "result injection returned" }
            }
            val runnable = runnable ?: EvalJsRunnable(view, url, getJs()).also {
                runnable = it
            }
            mHandler.removeCallbacks(runnable)
            diagnostic { "eval scheduled delay=${100L + delayTime}" }
            mHandler.postDelayed(runnable, 100L + delayTime)
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?
        ) {
            if (view != null && ownsWebView(view, navigationContext)) {
                handler?.proceed()
            } else {
                handler?.cancel()
            }
        }

        private inner class EvalJsRunnable(
            webView: WebView,
            private val url: String,
            mJavaScript: String
        ) : Runnable {
            private var retry = 0
            private val intervals = listOf(200L, 400L, 600L, 800L, 1000L)
            private val mWebView: WeakReference<WebView> = WeakReference(webView)
            private val jsStr = if (isRule) {
                "$getInjectionString\n$mJavaScript"
            } else mJavaScript
            override fun run() {
                diagnostic { "eval runnable entered retry=$retry" }
                val webView = mWebView.get() ?: return
                diagnosticCallback(webView, navigationContext) { "eval runnable" }
                if (!ownsWebView(webView, navigationContext)) return
                diagnostic { "evaluateJavascript entered retry=$retry" }
                webView.evaluateJavascript(jsStr) {
                    diagnosticCallback(webView, navigationContext) { "eval callback length=${it?.length} null=${it == "null"}" }
                    if (ownsWebView(webView, navigationContext)) {
                        handleResult(it, webView)
                    }
                }
                diagnostic { "evaluateJavascript returned retry=$retry" }
            }

            private fun handleResult(result: String, webView: WebView) {
                if (result.isNotEmpty() && result != "null") {
                    Coroutine.async {
                        diagnostic { "build response entered" }
                        val content = StringEscapeUtils.unescapeJson(result)
                            .replace(quoteRegex, "")
                        buildStrResponse(content)
                    }.onSuccess {
                        diagnosticCallback(webView, navigationContext) { "response onSuccess" }
                        if (ownsWebView(webView, navigationContext)) {
                            callback?.onResult(it)
                            destroy()
                        }
                    }.onError {
                        diagnosticCallback(webView, navigationContext) { "response onError ${it.javaClass.simpleName}" }
                        if (ownsWebView(webView, navigationContext)) {
                            callback?.onError(it)
                            destroy()
                        }
                    }
                    return
                }
                if (retry > 30) {
                    callback?.onError(NoStackTraceException("js执行超时"))
                    destroy()
                    return
                }
                val nextDelay = if (retry < intervals.size) {
                    intervals[retry]
                } else {
                    intervals.last()
                }
                retry++
                diagnostic { "eval retry scheduled retry=$retry delay=$nextDelay" }
                mHandler.postDelayed(this@EvalJsRunnable, nextDelay)
            }

            private fun buildStrResponse(content: String): StrResponse {
                if (!isRedirect) {
                    return StrResponse(url, content)
                }
                val originUrl = this@BackstageWebView.url ?: url
                val originResponse = Response.Builder()
                    .code(302)
                    .request(Request.Builder().url(originUrl).build())
                    .protocol(Protocol.HTTP_1_1)
                    .message("Found")
                    .build()
                val response = Response.Builder()
                    .code(200)
                    .request(Request.Builder().url(url).build())
                    .protocol(Protocol.HTTP_1_1)
                    .message("OK")
                    .priorResponse(originResponse)
                    .build()
                return StrResponse(response, content)
            }
        }

    }

    private inner class SnifferWebClient(
        private val navigationContext: CoroutineContext
    ) : WebViewClient() {

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            diagnosticCallback(view, navigationContext) { "sniffer pageStarted urlHash=${url?.hashCode()}" }
            super.onPageStarted(view, url, favicon)
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            diagnosticCallback(view, navigationContext) { "sniffer pageCommit urlHash=${url?.hashCode()}" }
            super.onPageCommitVisible(view, url)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            diagnosticCallback(view, navigationContext) { "sniffer pageError code=${error.errorCode} mainFrame=${request.isForMainFrame}" }
            super.onReceivedError(view, request, error)
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            diagnosticCallback(view, navigationContext) { "sniffer override request" }
            if (!ownsWebView(view, navigationContext)) return true
            if (shouldOverrideUrlLoading(request.url.toString())) {
                return true
            }
            return super.shouldOverrideUrlLoading(view, request)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "KotlinRedundantDiagnosticSuppress")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            diagnosticCallback(view, navigationContext) { "sniffer override url" }
            if (!ownsWebView(view, navigationContext)) return true
            if (shouldOverrideUrlLoading(url)) {
                return true
            }
            return super.shouldOverrideUrlLoading(view, url)
        }

        private fun shouldOverrideUrlLoading(requestUrl: String): Boolean {
            overrideUrlRegex?.let {
                if (requestUrl.matches(it.toRegex())) {
                    diagnostic { "sniffer override matched" }
                    try {
                        val response = StrResponse(url!!, requestUrl)
                        callback?.onResult(response)
                    } catch (e: Exception) {
                        callback?.onError(e)
                    }
                    destroy()
                    return true
                }
            }
            return false
        }

        override fun onLoadResource(view: WebView, resUrl: String) {
            diagnosticCallback(view, navigationContext) { "sniffer resource urlHash=${resUrl.hashCode()}" }
            if (!ownsWebView(view, navigationContext)) return
            sourceRegex?.let {
                if (resUrl.matches(it.toRegex())) {
                    diagnostic { "sniffer resource matched" }
                    try {
                        val response = StrResponse(url!!, resUrl)
                        callback?.onResult(response)
                    } catch (e: Exception) {
                        callback?.onError(e)
                    }
                    destroy()
                }
            }
        }

        override fun onPageFinished(webView: WebView, url: String) {
            diagnosticCallback(webView, navigationContext) { "sniffer pageFinished urlHash=${url.hashCode()}" }
            if (!ownsWebView(webView, navigationContext)) return
            setCookie(url)
            if (!javaScript.isNullOrEmpty()) {
                val runnable = LoadJsRunnable(webView, javaScript)
                diagnostic { "sniffer script scheduled delay=${100L + delayTime}" }
                mHandler.postDelayed(runnable, 100L + delayTime)
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?
        ) {
            if (view != null && ownsWebView(view, navigationContext)) {
                handler?.proceed()
            } else {
                handler?.cancel()
            }
        }

        private inner class LoadJsRunnable(
            webView: WebView,
            private val mJavaScript: String?
        ) : Runnable {
            private val mWebView: WeakReference<WebView> = WeakReference(webView)
            override fun run() {
                diagnostic { "sniffer runnable entered" }
                val webView = mWebView.get() ?: return
                diagnosticCallback(webView, navigationContext) { "sniffer runnable" }
                if (ownsWebView(webView, navigationContext)) {
                    diagnostic { "sniffer script loadUrl entered" }
                    webView.loadUrl("javascript:${mJavaScript}")
                    diagnostic { "sniffer script loadUrl returned" }
                }
            }
        }

    }

    companion object {
        const val JS = "document.documentElement.outerHTML"
        private val quoteRegex = "^\"|\"$".toRegex()
    }

    abstract class Callback {
        abstract fun onResult(response: StrResponse)
        abstract fun onError(error: Throwable)
    }
}
