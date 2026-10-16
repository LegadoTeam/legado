package io.legado.app.help.webView

import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Observes an in-flight test without changing its timeout, assertions, or WebView state. */
class WebViewDiagnosticsRule(private val watchFromStart: Boolean = true) : TestWatcher() {
    private var wasEnabled = false
    private var testName = ""
    private var watcher: Thread? = null
    @Volatile private var watchId = 0

    override fun starting(description: Description) {
        wasEnabled = WebViewDiagnostics.enabled
        WebViewDiagnostics.enabled = true
        testName = "${description.testClass.simpleName}-${description.methodName}"
        WebViewDiagnostics.record("test") { "start $testName" }
        if (watchFromStart) watch()
    }

    fun watch(stage: String = "request") {
        stopWatching()
        val id = watchId
        val label = "$testName-$stage-active"
        watcher = Thread({
            try {
                Thread.sleep(5_000)
            } catch (_: InterruptedException) {
                return@Thread
            }
            WebViewDiagnostics.captureActive(label) { watchId == id }
        }, "WebViewDiagnosticWatchdog").apply { isDaemon = true; start() }
    }

    fun stopWatching() {
        watchId++
        watcher?.interrupt()
        watcher = null
    }

    override fun failed(error: Throwable, description: Description) {
        WebViewDiagnostics.record("test") { "failed $testName error=${error.javaClass.simpleName}" }
        WebViewDiagnostics.dump("$testName-failure")
    }

    override fun finished(description: Description) {
        stopWatching()
        WebViewDiagnostics.record("test") { "finished $testName" }
        WebViewDiagnostics.dump("$testName-finished", threads = false)
        WebViewDiagnostics.enabled = wasEnabled
    }
}
