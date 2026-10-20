package io.legado.app.help.webView

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import io.legado.app.BuildConfig
import splitties.init.appCtx
import java.io.File
import java.util.ArrayDeque

/** Temporary bounded evidence recorder, enabled explicitly by UI tests in debug builds only. */
object WebViewDiagnostics {
    @Volatile
    var enabled = false
        set(value) { field = BuildConfig.DEBUG && value }

    private const val LIMIT = 512
    private val events = ArrayDeque<String>()
    private val lastStages = LinkedHashMap<String, String>()

    fun record(owner: String, stage: () -> String) {
        if (!enabled) return
        // A diagnostic accessor must not change a request's outcome or hold the recorder lock.
        val detail = runCatching(stage).getOrElse { "diagnostic error=${it.javaClass.simpleName}" }
        val event = "${SystemClock.uptimeMillis()} ${Thread.currentThread().name} $owner ${detail.take(2048)}"
        synchronized(this) {
            if (events.size == LIMIT) events.removeFirst()
            events.addLast(event)
            lastStages[owner] = event
            if (lastStages.size > 64) lastStages.remove(lastStages.keys.first())
        }
    }

    fun dump(label: String, threads: Boolean = true) {
        if (!enabled) return
        runCatching {
            val snapshot = synchronized(this) {
                "LAST STAGES\n${lastStages.values.joinToString("\n")}\n\nEVENTS\n${events.joinToString("\n")}"
            } + if (threads) {
                "\n\nTHREADS\n" + Thread.getAllStackTraces().entries.joinToString("\n\n") { (thread, stack) ->
                    "${thread.name}: ${thread.state}\n${stack.joinToString("\n")}"
                }
            } else ""
            File(appCtx.getExternalFilesDir("ui-regression"), "$label.txt").apply {
                parentFile?.mkdirs()
                writeText(snapshot)
            }
        }.onFailure { android.util.Log.e("WebViewLifecycle", "Cannot save $label", it) }
    }

    fun captureActive(label: String, isCurrent: () -> Boolean) {
        if (!enabled || !isCurrent()) return
        record("observer") { "5s reached; main snapshot pending label=$label" }
        // Preserve the live stack even if main cannot run the observational snapshot below.
        dump(label)
        val postedAt = SystemClock.uptimeMillis()
        Handler(Looper.getMainLooper()).post {
            if (!enabled || !isCurrent()) return@post
            record("observer") { "main snapshot entered delay=${SystemClock.uptimeMillis() - postedAt}ms label=$label" }
            runCatching { WebViewPool.diagnosticSnapshot(label, isCurrent) }
                .onFailure { record("observer") { "main snapshot error=${it.javaClass.simpleName}" } }
            record("observer") { "main snapshot returned label=$label" }
            Thread { dump("$label-main", threads = false) }.apply { isDaemon = true; start() }
        }
    }

    // Called on main only; no script or renderer responsiveness probe is submitted.
    fun describe(view: WebView): String {
        val renderer = if (Build.VERSION.SDK_INT >= 29) view.webViewRenderProcess?.let { System.identityHashCode(it) } else null
        val client = if (Build.VERSION.SDK_INT >= 26) System.identityHashCode(view.webViewClient) else null
        return "view=${System.identityHashCode(view)} urlHash=${view.url?.hashCode()} originalUrlHash=${view.originalUrl?.hashCode()} " +
            "title=${view.title?.take(80)} progress=${view.progress} js=${view.settings.javaScriptEnabled} " +
            "attached=${view.isAttachedToWindow} window=${view.windowVisibility} visibility=${view.visibility} " +
            "shown=${view.isShown} focus=${view.hasWindowFocus()} renderer=$renderer client=$client"
    }
}
