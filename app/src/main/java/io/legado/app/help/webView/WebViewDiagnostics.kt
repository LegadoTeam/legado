package io.legado.app.help.webView

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import splitties.init.appCtx
import java.io.File
import java.util.ArrayDeque

/** Temporary bounded evidence recorder; it never changes a WebView or evaluates JavaScript. */
object WebViewDiagnostics {
    private const val LIMIT = 512
    private val events = ArrayDeque<String>()
    private val lastStages = LinkedHashMap<String, String>()

    @Synchronized
    fun record(owner: String, stage: String) {
        val event = "${SystemClock.uptimeMillis()} ${Thread.currentThread().name} $owner ${stage.take(2048)}"
        if (events.size == LIMIT) events.removeFirst()
        events.addLast(event)
        lastStages[owner] = event
        if (lastStages.size > 64) lastStages.remove(lastStages.keys.first())
    }

    private fun buffered(): String = synchronized(this) {
        "LAST STAGES\n${lastStages.values.joinToString("\n")}\n\nEVENTS\n${events.joinToString("\n")}"
    }

    fun dump(label: String, threads: Boolean = true) {
        val snapshot = buffered() + if (threads) {
            "\n\nTHREADS\n" + Thread.getAllStackTraces().entries.joinToString("\n\n") { (thread, stack) ->
                "${thread.name}: ${thread.state}\n${stack.joinToString("\n")}"
            }
        } else ""
        runCatching {
            File(appCtx.getExternalFilesDir("ui-regression"), "$label.txt").apply {
                parentFile?.mkdirs()
                writeText(snapshot)
            }
        }.onFailure { android.util.Log.e("WebViewLifecycle", "Cannot save $label", it) }
    }

    fun captureActive(label: String) {
        record("observer", "5s reached; main snapshot pending label=$label")
        // Save evidence independently before asking the possibly blocked main thread for view state.
        dump(label)
        val postedAt = SystemClock.uptimeMillis()
        Handler(Looper.getMainLooper()).post {
            record("observer", "main snapshot entered delay=${SystemClock.uptimeMillis() - postedAt}ms label=$label")
            runCatching { WebViewPool.diagnosticSnapshot() }
                .onFailure { record("observer", "main snapshot error=$it") }
            record("observer", "main snapshot returned label=$label")
            Thread { dump("$label-main", threads = false) }.apply { isDaemon = true; start() }
        }
    }

    // Called on main only. Renderer identity is observational; no responsiveness probe is submitted.
    fun describe(view: WebView): String {
        val renderer = if (Build.VERSION.SDK_INT >= 29) view.webViewRenderProcess?.let { System.identityHashCode(it) } else null
        val client = if (Build.VERSION.SDK_INT >= 26) System.identityHashCode(view.webViewClient) else null
        return "view=${System.identityHashCode(view)} url=${view.url} originalUrl=${view.originalUrl} progress=${view.progress} js=${view.settings.javaScriptEnabled} attached=${view.isAttachedToWindow} window=${view.windowVisibility} visibility=${view.visibility} shown=${view.isShown} focus=${view.hasWindowFocus()} renderer=$renderer client=$client"
    }
}
