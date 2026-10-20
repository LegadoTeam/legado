package io.legado.app.help.webView

import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

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
            captureProcesses(label, id)
        }, "WebViewDiagnosticWatchdog").apply { isDaemon = true; start() }
    }

    fun stopWatching() {
        watchId++
        watcher?.interrupt()
        watcher = null
    }

    private fun captureProcesses(label: String, id: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = instrumentation.targetContext.getExternalFilesDir("ui-regression")
        // Capture renderer process/frozen state without sending it JavaScript or changing priority.
        for ((name, command) in listOf(
            "processes" to "dumpsys activity processes",
            "provider" to "dumpsys webviewupdate",
        )) {
            if (watchId != id) return
            val started = SystemClock.uptimeMillis()
            runCatching {
                val output = ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand(command)
                ).bufferedReader().use { it.readText() }
                File(directory, "$label-$name.txt").apply {
                    parentFile?.mkdirs()
                    writeText("appPid=${Process.myPid()} started=$started finished=${SystemClock.uptimeMillis()} current=${watchId == id}\n$output")
                }
            }.onFailure { WebViewDiagnostics.record("observer") { "$name snapshot failed: ${it.javaClass.simpleName}" } }
        }
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
