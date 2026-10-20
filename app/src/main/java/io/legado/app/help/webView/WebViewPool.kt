package io.legado.app.help.webView

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.os.Build
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.rss.read.VisibleWebView
import io.legado.app.utils.setDarkeningAllowed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.util.Stack
import kotlin.math.max
import kotlin.random.Random

object WebViewPool {
    const val BLANK_HTML = "about:blank"
    const val DATA_HTML = "data:text/html;charset=utf-8;base64,"
    // 未使用的、已预初始化的WebView池 (使用栈结构，后进先出，复用缓存)
    private val idlePool = Stack<PooledWebView>()
    // 正在使用的WebView集合
    private val inUsePool = mutableMapOf<String, PooledWebView>()

    private var needInitialize = true
    private val CACHED_WEB_VIEW_MAX_NUM = max(AppConfig.threadCount / 10, 5) // 池子总容量（闲置+使用）
    private const val IDLE_TIME_OUT: Long = 5 * 60 * 1000 // 闲置5分钟后销毁
    private const val IDLE_TIME_OUT_LAST: Long = 30 * 60 * 1000 // 最后一个闲置30分钟后销毁
    private val cleanupScope by lazy {
        CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    }
    private var cleanupJob: Job? = null

    // 获取一个WebView
    @Synchronized
    fun acquire(context: Context): PooledWebView {
        WebViewDiagnostics.record("pool") { "acquire entered idle=${idlePool.size} inUse=${inUsePool.size}" }
        val pooledWebView = if (idlePool.isNotEmpty()) {
            idlePool.pop() // 复用闲置实例
        } else {
            if (needInitialize) {
                needInitialize = false
                startCleanupTimer()
            }
            createNewWebView() // 创建新实例
        }
        pooledWebView.upContext(context).apply {
            realWebView.settings.apply {
                setDarkeningAllowed(AppConfig.isNightTheme) //设置是否夜间
                userAgentString = AppConfig.userAgent
            }
            isInUse = true
        }
        inUsePool[pooledWebView.id] = pooledWebView
        WebViewDiagnostics.record(pooledWebView.id) {
            "acquire returned generation=${pooledWebView.recycleGeneration} ${WebViewDiagnostics.describe(pooledWebView.realWebView)}"
        }
        return pooledWebView
    }

    // 释放WebView回池
    @Synchronized
    fun release(pooledWebView: PooledWebView) {
        WebViewDiagnostics.record(pooledWebView.id) { "release entered owned=${pooledWebView.id in inUsePool}" }
        if (inUsePool.remove(pooledWebView.id) == null) return
        val recycleGeneration = ++pooledWebView.recycleGeneration
        val recycleUrl = "$BLANK_HTML?legado-recycle=$recycleGeneration"
        // 重置WebView状态
        pooledWebView.realWebView.run {
            (parent as? ViewGroup)?.removeView(this)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            stopLoading()
            removeJavascriptInterface(WebJsExtensions.nameBasic)
            removeJavascriptInterface(WebJsExtensions.nameJava)
            removeJavascriptInterface(WebJsExtensions.nameSource)
            removeJavascriptInterface(WebJsExtensions.nameCache)
            clearFocus() //清除焦点
            isNestedScrollingEnabled = false
            setOnLongClickListener(null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setOnScrollChangeListener(null)
            }
            setDownloadListener(null)
            outlineProvider = null
            clipToOutline = false
            webChromeClient = null
            clearFormData() //清除表单数据
            clearMatches() //清除查找匹配项
            clearDisappearingChildren() //清除消失中的子视图
            clearAnimation() //清除动画
            pooledWebView.upContext(appCtx)
            if (idlePool.size >= CACHED_WEB_VIEW_MAX_NUM - inUsePool.size) {
                // 池子已满，直接销毁
                WebViewDiagnostics.record(pooledWebView.id) { "pool full; destroy entered" }
                pooledWebView.realWebView.destroy()
                WebViewDiagnostics.record(pooledWebView.id) { "destroy returned" }
                return
            }
            webViewClient = object: WebViewClient() {
                @SuppressLint("SetJavaScriptEnabled")
                override fun onPageFinished(view: WebView?, url: String?) {
                    WebViewDiagnostics.record(pooledWebView.id) {
                        "recycle callback generation=$recycleGeneration matchingUrl=${url == recycleUrl} isInUse=${pooledWebView.isInUse} borrowed=${pooledWebView.id in inUsePool}"
                    }
                    if (url != recycleUrl) return
                    synchronized(this@WebViewPool) {
                        if (!pooledWebView.isInUse || pooledWebView.id in inUsePool) return
                        view?.let { webview ->
                            webview.settings.apply {
                                javaScriptEnabled = false
                                javaScriptEnabled = true // 禁用再启用来重置js环境，注意需要禁用的订阅源需要再次执行
                                blockNetworkImage = false // 确保允许加载网络图片
                                cacheMode = WebSettings.LOAD_DEFAULT // 重置缓存模式
                                useWideViewPort = false // 恢复默认关闭宽视模式
                                loadWithOverviewMode = false // 恢复默认
                                textZoom = 100
                            }
                            WebViewDiagnostics.record(pooledWebView.id) { "recycle onPause entered generation=$recycleGeneration" }
                            webview.onPause()
                            WebViewDiagnostics.record(pooledWebView.id) { "recycle onPause returned generation=$recycleGeneration" }
                        }
                        pooledWebView.isInUse = false
                        pooledWebView.lastUseTime = System.currentTimeMillis()
                        idlePool.push(pooledWebView)
                        WebViewDiagnostics.record(pooledWebView.id) { "recycle complete generation=$recycleGeneration idle=${idlePool.size}" }
                    }
                }
            }
            WebViewDiagnostics.record(pooledWebView.id) { "recycle loadUrl entered generation=$recycleGeneration" }
            loadUrl(recycleUrl)
            WebViewDiagnostics.record(pooledWebView.id) { "recycle loadUrl returned generation=$recycleGeneration" }
        }
    }

    @Synchronized
    internal fun diagnosticSnapshot(label: String, isCurrent: () -> Boolean) {
        if (!WebViewDiagnostics.enabled) return
        WebViewDiagnostics.record("pool") { "snapshot idle=${idlePool.size} inUse=${inUsePool.size}" }
        (inUsePool.values + idlePool).forEach { pooled ->
            WebViewDiagnostics.record(pooled.id) {
                "snapshot borrowed=${pooled.id in inUsePool} generation=${pooled.recycleGeneration} ${WebViewDiagnostics.describe(pooled.realWebView)}"
            }
            if (pooled.id in inUsePool && isCurrent()) {
                val generation = pooled.recycleGeneration
                val started = android.os.SystemClock.uptimeMillis()
                WebViewDiagnostics.record(pooled.id) { "DOM probe submitted generation=$generation label=$label" }
                runCatching {
                    pooled.realWebView.evaluateJavascript(
                        "JSON.stringify({readyState:document.readyState,title:document.title,body:document.body&&document.body.textContent.slice(0,512)})"
                    ) { result ->
                        val sameLease = inUsePool[pooled.id] === pooled && pooled.recycleGeneration == generation
                        WebViewDiagnostics.record(pooled.id) {
                            "DOM probe callback elapsed=${android.os.SystemClock.uptimeMillis() - started}ms current=${isCurrent()} sameLease=$sameLease " +
                                if (isCurrent() && sameLease) "result=$result" else "result=ignored"
                        }
                        Thread {
                            WebViewDiagnostics.dump("$label-dom-${System.identityHashCode(pooled.realWebView)}", threads = false)
                        }.apply { isDaemon = true; start() }
                    }
                    WebViewDiagnostics.record(pooled.id) { "DOM probe submission returned generation=$generation" }
                }.onFailure {
                    WebViewDiagnostics.record(pooled.id) { "DOM probe submission failed error=${it.javaClass.simpleName}" }
                }
            }
        }
    }

    private fun createNewWebView(): PooledWebView {
        val webView = VisibleWebView(MutableContextWrapper(appCtx))
        preInitWebView(webView)
        return PooledWebView(webView, generateId())
    }

    private fun generateId(): String {
        return "web_${System.currentTimeMillis()}_${Random.nextLong()}"
    }

    // 初始化
    @SuppressLint("SetJavaScriptEnabled")
    private fun preInitWebView(webView: WebView) {
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        webView.settings.apply {
            javaScriptEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowContentAccess = true
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = 100
        }
    }

    // 定时清理闲置过久的WebView
    private fun startCleanupTimer() {
        if (cleanupJob?.isActive == true) return
        cleanupJob = cleanupScope.launch {
            while (true) {
                delay(30_000) // 每30秒执行一次清理
                val now = System.currentTimeMillis()
                val toRemove = mutableListOf<PooledWebView>()
                var shouldCancel = false
                synchronized(this@WebViewPool) {
                    for ((index, pooled) in idlePool.withIndex()) {
                        val timeout = if (index == 0) {
                            IDLE_TIME_OUT_LAST
                        } else {
                            IDLE_TIME_OUT
                        }
                        if (now - pooled.lastUseTime > timeout) {
                            toRemove.add(pooled)
                        }
                    }
                    toRemove.forEach { pooled ->
                        idlePool.remove(pooled)
                        try {
                            WebViewDiagnostics.record(pooled.id) { "idle cleanup destroy generation=${pooled.recycleGeneration}" }
                            pooled.realWebView.destroy()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    if (idlePool.isEmpty()) {
                        shouldCancel = true
                    }
                }
                if (shouldCancel) {
                    needInitialize = true
                    this@launch.cancel()
                }
            }
        }
    }

}
