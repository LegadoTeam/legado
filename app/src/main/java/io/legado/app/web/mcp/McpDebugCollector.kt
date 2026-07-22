package io.legado.app.web.mcp

import io.legado.app.data.entities.BookSource
import io.legado.app.model.Debug
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull

class McpDebugCollector : Debug.Callback {

    private val lines = StringBuilder()
    private val finished = CompletableDeferred<Unit>()
    private var truncated = false

    override fun printLog(state: Int, msg: String) {
        if (state in notPrintStates) return
        synchronized(lines) {
            appendBounded(msg)
        }
        if (state == -1 || state == 1000) {
            finished.complete(Unit)
        }
    }

    fun snapshot(): String = synchronized(lines) { lines.toString() }

    private fun appendBounded(message: String) {
        if (truncated) return
        val line = "$message\n"
        val remaining = MAX_LOG_CHARS - lines.length
        if (line.length <= remaining) {
            lines.append(line)
            return
        }
        val contentSize = (remaining - TRUNCATED_MARKER.length).coerceAtLeast(0)
        if (contentSize > 0) {
            lines.append(line, 0, contentSize.coerceAtMost(line.length))
        }
        val markerSize = (MAX_LOG_CHARS - lines.length).coerceAtLeast(0)
        if (markerSize > 0) {
            lines.append(TRUNCATED_MARKER, 0, markerSize.coerceAtMost(TRUNCATED_MARKER.length))
        }
        truncated = true
    }

    suspend fun awaitFinished(timeoutMs: Long): Boolean {
        return withTimeoutOrNull(timeoutMs) { finished.await() } != null
    }

    suspend fun collect(
        scope: CoroutineScope,
        source: BookSource,
        key: String,
        timeoutMs: Long,
    ): Pair<String, Boolean> {
        check(Debug.tryAcquireCallback(this)) { "调试通道占用中,稍后重试" }
        try {
            Debug.startDebug(scope, source, key)
            val done = awaitFinished(timeoutMs)
            return snapshot() to !done
        } catch (error: CancellationException) {
            throw error
        } finally {
            Debug.cancelDebug(this)
        }
    }

    private companion object {
        const val MAX_LOG_CHARS = McpFormat.TRUNCATE_LIMIT
        const val TRUNCATED_MARKER = "\n…[调试日志已截断]\n"
        val notPrintStates = setOf(10, 20, 30, 40)
    }
}
