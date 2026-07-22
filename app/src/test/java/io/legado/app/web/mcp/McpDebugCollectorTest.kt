package io.legado.app.web.mcp

import io.legado.app.data.entities.BookSource
import io.legado.app.model.Debug
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class McpDebugCollectorTest {

    @Test
    fun accumulateAndFinishSignals() = runBlocking {
        val collector = McpDebugCollector()
        collector.printLog(1, "step1")
        collector.printLog(10, "raw html ignored")
        collector.printLog(1000, "done")

        assertTrue(collector.awaitFinished(1_000))
        assertEquals("step1\ndone\n", collector.snapshot())

        val failed = McpDebugCollector()
        failed.printLog(-1, "boom")
        assertTrue(failed.awaitFinished(1_000))
        assertTrue(failed.snapshot().contains("boom"))
    }

    @Test
    fun timeoutReturnsPartialOutput() = runBlocking {
        val collector = McpDebugCollector()
        collector.printLog(1, "partial")

        assertFalse(collector.awaitFinished(25))
        assertEquals("partial\n", collector.snapshot())
    }

    @Test
    fun logBufferIsBoundedButStillAcceptsCompletion() = runBlocking {
        val collector = McpDebugCollector()
        collector.printLog(1, "x".repeat(McpFormat.TRUNCATE_LIMIT * 2))
        collector.printLog(1000, "done")

        assertTrue(collector.awaitFinished(1_000))
        val snapshot = collector.snapshot()
        assertTrue(snapshot.length <= McpFormat.TRUNCATE_LIMIT)
        assertTrue(snapshot.contains("调试日志已截断"))
    }

    @Test
    fun busyCollectorDoesNotReplaceExistingCallback() {
        val existing = object : Debug.Callback {
            override fun printLog(state: Int, msg: String) = Unit
        }
        assertTrue(Debug.tryAcquireCallback(existing))
        try {
            val error = assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    McpDebugCollector().collect(
                        scope = this,
                        source = BookSource(bookSourceUrl = "https://example.com"),
                        key = "test",
                        timeoutMs = 1,
                    )
                }
            }
            assertTrue(error.message.orEmpty().contains("调试通道占用中"))
            assertSame(existing, Debug.callback)
        } finally {
            Debug.cancelDebug(existing)
        }
    }

    @Test
    fun outerCancellationIsNotConvertedToTimeout() {
        val collector = McpDebugCollector()
        assertThrows(CancellationException::class.java) {
            runBlocking {
                withTimeout(25) {
                    collector.awaitFinished(60_000)
                }
            }
        }
    }
}
