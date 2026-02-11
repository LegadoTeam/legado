package io.legado.app.help.webView

import android.content.Context
import android.content.MutableContextWrapper
import io.legado.app.ui.rss.read.VisibleWebView

class PooledWebView(
    val realWebView: VisibleWebView, // 真正的WebView实例
    val id: String // 唯一标识
) {
    var isInUse: Boolean = false // 是否正在被使用
    var lastUseTime: Long = 0 // 最后一次被使用的时间戳

    fun upContext(context: Context): PooledWebView {
        (realWebView.context as? MutableContextWrapper)?.let {
            if (it.baseContext != context) {
                it.baseContext = context
            }
        }
        return this
    }
}