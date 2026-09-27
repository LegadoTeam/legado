package io.legado.app.help.source

import android.webkit.JavascriptInterface
import com.script.rhino.runScriptWithContext
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.config.AppConfig
import io.legado.app.model.VideoPlay
import kotlin.coroutines.CoroutineContext

/** WebView calls its Java bridge on a different thread from the rule's Rhino context. */
internal fun BaseSource.withSourceNavigationContext(context: CoroutineContext): BaseSource {
    if (context[SuppressSourceNavigation] == null) return this
    val original = this
    return object : BaseSource by original {
        private fun allowed() = !shouldSuppressSourceNavigation(AppConfig.blockSourceNavigation, context)

        @JavascriptInterface
        override fun login() = runScriptWithContext(context) { original.login() }

        @JavascriptInterface
        override fun openVideoPlayer(url: String, title: String) =
            openVideoPlayer(url, title, VideoPlay.defaultFloatWindow)

        @JavascriptInterface
        override fun openVideoPlayer(url: String, title: String, isFloat: Boolean) {
            if (allowed()) original.openVideoPlayer(url, title, isFloat)
        }

        @JavascriptInterface
        override fun openUrl(url: String) = openUrl(url, null)

        @JavascriptInterface
        override fun openUrl(url: String, mimeType: String?) {
            if (allowed()) original.openUrl(url, mimeType)
        }
    }
}
