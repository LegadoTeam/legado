package io.legado.app.help

import io.legado.app.exception.NoStackTraceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SourceLockTest {

    @Test
    fun singleFlightMergesConcurrentCalls() {
        val workers = 16
        val executor = Executors.newFixedThreadPool(workers)
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val actionStarted = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val executions = AtomicInteger()
        val key = uniqueKey("flight")

        try {
            val futures = (1..workers).map {
                executor.submit {
                    ready.countDown()
                    start.await(5, TimeUnit.SECONDS)
                    SourceLock.singleFlight(key, 5_000) {
                        executions.incrementAndGet()
                        actionStarted.countDown()
                        releaseAction.await(5, TimeUnit.SECONDS)
                    }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(actionStarted.await(5, TimeUnit.SECONDS))
            Thread.sleep(100)
            releaseAction.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, executions.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun failedSingleFlightCanRetry() {
        val key = uniqueKey("retry")
        assertThrows(IllegalStateException::class.java) {
            SourceLock.singleFlight(key, 100) { error("failed") }
        }

        var retried = false
        SourceLock.singleFlight(key, 100) { retried = true }

        assertTrue(retried)
    }

    @Test
    fun lockSerializesEveryCaller() {
        val workers = 24
        val executor = Executors.newFixedThreadPool(8)
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val executions = AtomicInteger()
        val key = uniqueKey("lock")

        try {
            val futures = (1..workers).map {
                executor.submit {
                    SourceLock.lock(key, 5_000) {
                        val current = active.incrementAndGet()
                        maxActive.accumulateAndGet(current, ::maxOf)
                        Thread.sleep(5)
                        executions.incrementAndGet()
                        active.decrementAndGet()
                    }
                }
            }
            futures.forEach { it.get(5, TimeUnit.SECONDS) }

            assertEquals(workers, executions.get())
            assertEquals(1, maxActive.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun lockWaitHonorsTimeout() {
        val executor = Executors.newSingleThreadExecutor()
        val actionStarted = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val key = uniqueKey("timeout")

        try {
            val holder = executor.submit {
                SourceLock.lock(key, 1_000) {
                    actionStarted.countDown()
                    releaseAction.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue(actionStarted.await(5, TimeUnit.SECONDS))
            assertThrows(NoStackTraceException::class.java) {
                SourceLock.lock(key, 10) {}
            }
            releaseAction.countDown()
            holder.get(5, TimeUnit.SECONDS)
        } finally {
            releaseAction.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun tickReturnsUniqueNonNegativeSequenceAcrossThreads() {
        val calls = 200
        val executor = Executors.newFixedThreadPool(8)
        val key = uniqueKey("tick")

        try {
            val values = (1..calls).map {
                executor.submit<Int> { SourceLock.tick(key) }
            }.map { it.get(5, TimeUnit.SECONDS) }.sorted()

            assertEquals((0 until calls).toList(), values)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun rejectsUnboundedWaits() {
        val key = uniqueKey("wait")
        assertThrows(NoStackTraceException::class.java) {
            SourceLock.lock(key, -1) {}
        }
        assertThrows(NoStackTraceException::class.java) {
            SourceLock.singleFlight(key, 300_001) {}
        }
    }

    private fun uniqueKey(prefix: String): String = "$prefix-${UUID.randomUUID()}"
}
