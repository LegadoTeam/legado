package io.legado.app.ui.rss.read

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView

class VisibleWebView(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    var hasBeenAttachedToWindow = false
        private set

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        hasBeenAttachedToWindow = true
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(VISIBLE)
    }

}
