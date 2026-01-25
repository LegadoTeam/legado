package io.legado.app.ui.widget.dialog

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.databinding.DialogWebViewBinding
import io.legado.app.help.WebCacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.JS_INJECTION
import io.legado.app.help.webView.WebJsExtensions.Companion.basicJs
import io.legado.app.help.webView.WebJsExtensions.Companion.nameBasic
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebJsExtensions.Companion.nameSource
import io.legado.app.help.webView.WebViewPool
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.browser.WebViewActivity.Companion.sessionShowWebLog
import io.legado.app.utils.invisible
import io.legado.app.utils.keepScreenOn
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.openUrl
import io.legado.app.utils.setLayout
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.toggleSystemBar
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.launch
import androidx.core.view.size
import io.legado.app.utils.get
import java.lang.ref.WeakReference

class BottomWebViewDialog() : BottomSheetDialogFragment(R.layout.dialog_web_view) {

    constructor(
        sourceKey: String,
        bookType: Int,
        url: String,
        html: String,
        preloadJs: String? = null
    ) : this() {
        arguments = Bundle().apply {
            putString("sourceKey", sourceKey)
            putInt("bookType", bookType)
            putString("url", url)
            putString("html", html)
            putString("preloadJs", preloadJs)
        }
    }

    private val binding by viewBinding(DialogWebViewBinding::bind)
    private val bottomSheet by lazy {
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
    }
    private val behavior by lazy {
        bottomSheet?.let { sheet ->
            BottomSheetBehavior.from(sheet)
        }
    }
    private lateinit var pooledWebView: PooledWebView
    private lateinit var currentWebView: WebView
    private var source: BaseSource? = null
    private var isFullScreen = false
    private var customWebViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originOrientation: Int? = null
    private var needClearHistory = true

    override fun onAttach(context: Context) {
        super.onAttach(context)
        pooledWebView = WebViewPool.acquire(context)
        currentWebView = pooledWebView.realWebView
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        bottomSheet?.let { sheet ->
            val layoutParams = sheet.layoutParams
            layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            sheet.layoutParams = layoutParams
        }
    }

    override fun show(manager: FragmentManager, tag: String?) {
        kotlin.runCatching {
            manager.beginTransaction().remove(this).commit()
            super.show(manager, tag)
        }.onFailure {
            AppLog.put("显示对话框失败 tag:$tag", it)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!AppConfig.isEInkMode) {
            view.setBackgroundColor(ThemeStore.backgroundColor())
        }
        binding.webViewContainer.addView(currentWebView)
        lifecycleScope.launch {
            val args = arguments
            if (args == null) {
                dismiss()
                return@launch
            }
            val sourceKey = args.getString("sourceKey") ?: return@launch
            val url = args.getString("url") ?: return@launch
            var html = args.getString("html") ?: return@launch
            args.getString("preloadJs")?.let { preloadJs ->
                html = if (html.contains("<head>")) {
                    html.replaceFirst(
                        "<head>",
                        "<head><script>(() => {$JS_INJECTION\n$preloadJs\n})();</script>"
                    )
                } else {
                    "<head><script>(() => {$JS_INJECTION\n$preloadJs\n})();</script></head>$html"
                }
            }
            appDb.bookSourceDao.getBookSource(sourceKey).let {
                if (it == null) {
                    activity?.toastOnUi("no find bookSource")
                    dismiss()
                    return@launch
                }
                source = it
            }
            val bookType = args.getInt("bookType", 0)
            val analyzeUrl = AnalyzeUrl(url, source = source, coroutineContext = coroutineContext)
            currentWebView.resumeTimers()
            currentWebView.onResume() //缓存库拿的需要激活
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                currentWebView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                    behavior?.isDraggable = scrollY == 0
                }
            }
            currentWebView.post {
                initWebView(analyzeUrl.url, html, analyzeUrl.headerMap, bookType)
                currentWebView.clearHistory()
            }
        }
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (binding.customWebView.size > 0) { //网页全屏
                    customWebViewCallback?.onCustomViewHidden()
                    return@setOnKeyListener true
                }
                if (currentWebView.canGoBack()) {
                    currentWebView.goBack()
                    return@setOnKeyListener true
                }
                dismiss()
                return@setOnKeyListener true
            }
            false
        }
    }

    private fun initWebView(
        url: String,
        html: String,
        headerMap: HashMap<String, String>,
        bookType: Int
    ) {
        currentWebView.webChromeClient = CustomWebChromeClient()
        currentWebView.addJavascriptInterface(JSInterface(this), nameBasic)
        currentWebView.webViewClient = CustomWebViewClient()
        currentWebView.settings.userAgentString = headerMap.get(AppConst.UA_NAME, true)
        source?.let { source ->
            (activity as? AppCompatActivity)?.let { currentActivity ->
                val webJsExtensions = WebJsExtensions(source, currentActivity, currentWebView, bookType)
                currentWebView.addJavascriptInterface(webJsExtensions, nameJava)
            }
            currentWebView.addJavascriptInterface(source, nameSource)
            currentWebView.addJavascriptInterface(WebCacheManager, nameCache)
        }
        currentWebView.loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
    }

    override fun onDestroyView() {
        customWebViewCallback?.onCustomViewHidden()
        WebViewPool.release(pooledWebView)
        originOrientation?.let {
            activity?.requestedOrientation = it
        }
        super.onDestroyView()
    }

    @Suppress("unused")
    private class JSInterface(dialog: BottomWebViewDialog) {
        private val dialogRef: WeakReference<BottomWebViewDialog> = WeakReference(dialog)
        @JavascriptInterface
        fun lockOrientation(orientation: String) {
            val fra = dialogRef.get() ?: return
            val ctx = fra.requireActivity()
            if (fra.isFullScreen && fra.dialog?.isShowing == true) {
                ctx.runOnUiThread {
                    ctx.requestedOrientation = when (orientation) {
                        "portrait", "portrait-primary" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        "portrait-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                        "landscape", "landscape-primary" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        "landscape-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                        "any", "unspecified" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
            }
        }

        @JavascriptInterface
        fun onCloseRequested() {
            val fra = dialogRef.get() ?: return
            if (fra.dialog?.isShowing == true) {
                fra.requireActivity().runOnUiThread {
                    fra.dismiss()
                }
            }
        }
    }

    inner class CustomWebChromeClient : WebChromeClient() {

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            isFullScreen = true
            binding.webViewContainer.invisible()
            binding.customWebView.addView(view)
            customWebViewCallback = callback
            dialog?.toggleSystemBar(false)
            dialog?.keepScreenOn(true)
            behavior?.state = BottomSheetBehavior.STATE_EXPANDED
            originOrientation = activity?.requestedOrientation
        }

        override fun onHideCustomView() {
            isFullScreen = false
            binding.webViewContainer.visible()
            binding.customWebView.removeAllViews()
            customWebViewCallback = null
            dialog?.toggleSystemBar(true)
            dialog?.keepScreenOn(false)
            originOrientation?.let {
                activity?.requestedOrientation = it
            }
        }

        /* 覆盖window.close() */
        override fun onCloseWindow(window: WebView?) {
            dismiss()
        }

        /* 监听网页日志 */
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            if (sessionShowWebLog) {
                val source = source ?: return false
                val consoleException =
                    Exception("${consoleMessage.messageLevel().name}: \n${consoleMessage.message()}\n-Line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}")
                val message = source.getTag() + ": ${consoleMessage.message()}"
                when (consoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.LOG -> AppLog.put(message)
                    ConsoleMessage.MessageLevel.DEBUG -> AppLog.put(message, consoleException)
                    ConsoleMessage.MessageLevel.WARNING -> AppLog.put(message, consoleException)
                    ConsoleMessage.MessageLevel.ERROR -> AppLog.put(message, consoleException)
                    ConsoleMessage.MessageLevel.TIP -> AppLog.put(message)
                    else -> AppLog.put(message)
                }
                return true
            }
            return false
        }
    }

    inner class CustomWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?, request: WebResourceRequest?
        ): Boolean {
            request?.let {
                return shouldOverrideUrlLoading(it.url)
            }
            return true
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "KotlinRedundantDiagnosticSuppress")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            url?.let {
                return shouldOverrideUrlLoading(it.toUri())
            }
            return true
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (needClearHistory) {
                needClearHistory = false
                currentWebView.clearHistory() //清除历史
            }
            super.onPageStarted(view, url, favicon)
            currentWebView.evaluateJavascript(basicJs, null)
        }

        private fun shouldOverrideUrlLoading(url: Uri): Boolean {
            return when (url.scheme) {
                "http", "https" -> false
                "legado", "yuedu" -> {
                    startActivity<OnLineImportActivity> {
                        data = url
                    }
                    return true
                }

                else -> {
                    binding.root.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
                        activity?.openUrl(url)
                    }
                    true
                }
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?, handler: SslErrorHandler?, error: SslError?
        ) {
            handler?.proceed()
        }
    }

}