package io.legado.app.help.webView

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.view.ViewGroup
import android.webkit.URLUtil
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.webView.WebJsExtensions.Companion.nameBasic
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebJsExtensions.Companion.nameSource
import io.legado.app.model.Download
import io.legado.app.ui.rss.read.VisibleWebView
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.setDarkeningAllowed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.net.URLDecoder
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
    private val cleanupScope by lazy { CoroutineScope(Dispatchers.IO + SupervisorJob()) }
    private var cleanupJob: Job? = null

    // 获取一个WebView
    @Synchronized
    fun acquire(context: Context): PooledWebView {
        val pooledWebView = if (idlePool.isNotEmpty()) {
            idlePool.pop().upContext(context) // 复用闲置实例
        } else {
            if (needInitialize) {
                needInitialize = false
                startCleanupTimer()
            }
            createNewWebView().upContext(context) // 创建新实例
        }
        pooledWebView.let {
            it.isInUse = true
            inUsePool[it.id] = it
        }
        return pooledWebView
    }

    // 释放WebView回池
    @Synchronized
    fun release(pooledWebView: PooledWebView) {
        if (inUsePool.remove(pooledWebView.id) == null) {
            pooledWebView.realWebView.destroy()
            return
        }
        // 重置WebView状态
        resetWebView(pooledWebView.realWebView)
        pooledWebView.upContext(MutableContextWrapper(appCtx))
        pooledWebView.isInUse = false
        if (idlePool.size < CACHED_WEB_VIEW_MAX_NUM - inUsePool.size) {
            pooledWebView.lastUseTime = System.currentTimeMillis()
            idlePool.push(pooledWebView)
        } else {
            // 池子已满，直接销毁
            pooledWebView.realWebView.destroy()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resetWebView(webView: WebView) = with(webView) {
        try {
            (parent as? ViewGroup)?.removeView(webView)
            webView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            stopLoading()
            clearFocus() //清除焦点
            webView.setOnLongClickListener(null)
            webView.setOnScrollChangeListener(null)
            webChromeClient = null
            webViewClient = WebViewClient()

//            webView.clearCache(true) //清除缓存
//            webView.clearHistory() //清除历史记录
            clearFormData() //清除表单数据
            clearMatches() //清除查找匹配项
//            webView.clearSslPreferences() //清除SSL首选项
//            clearDisappearingChildren() //清除消失中的子视图
            clearAnimation() //清除动画
            removeJavascriptInterface(nameBasic)
            removeJavascriptInterface(nameJava)
            removeJavascriptInterface(nameSource)
            removeJavascriptInterface(nameCache)
            settings.apply {
                javaScriptEnabled = false
                javaScriptEnabled = true // 禁用再启用来重置js环境，注意需要禁用的订阅源需要再次执行
                blockNetworkImage = false // 确保允许加载网络图片
                cacheMode = WebSettings.LOAD_DEFAULT // 重置缓存模式
                useWideViewPort = false // 恢复默认关闭宽视模式
                loadWithOverviewMode = false // 恢复默认
            }
            loadUrl(BLANK_HTML)
        } catch (e: Exception) {
            e.printStackTrace()
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
            setDarkeningAllowed(AppConfig.isNightTheme)
            textZoom = 100
        }
        webView.setDownloadListener { url, _, contentDisposition, _, _ ->
            var fileName = URLUtil.guessFileName(url, contentDisposition, null)
            fileName = URLDecoder.decode(fileName, "UTF-8")
            webView.longSnackbar(fileName, appCtx.getString(R.string.action_download)) {
                Download.start(appCtx, url, fileName)
            }
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