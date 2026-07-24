package io.legado.app.help.source

import io.legado.app.exception.NoStackTraceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SourceVerificationHelpTest {

    @Test
    fun concurrentRequestsOnTheSameOriginJoinAndKeepTheirRefetchUrl() {
        val workers = 12
        val registry = VerificationFlightRegistry()
        val executor = Executors.newFixedThreadPool(workers)
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val acquired = CountDownLatch(workers)
        val owners = AtomicInteger()
        val refetchUrls = ConcurrentLinkedQueue<String>()

        try {
            val futures = (1..workers).map { request ->
                executor.submit {
                    val originalUrl = "https://example.com/$request"
                    val flightKey = requireNotNull(
                        verificationFlightKey("source", 0, originalUrl)
                    )
                    ready.countDown()
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    val lease = registry.acquire(flightKey)
                    if (lease.owner) owners.incrementAndGet()
                    acquired.countDown()
                    try {
                        assertTrue(acquired.await(5, TimeUnit.SECONDS))
                        if (lease.owner) {
                            registry.complete(lease)
                        } else {
                            assertSame(
                                VerificationFlightWaitResult.Completed,
                                registry.await(lease, TimeUnit.SECONDS.toNanos(5))
                            )
                            refetchUrls += originalUrl
                        }
                    } finally {
                        registry.release(flightKey, lease)
                    }
                }
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, owners.get())
            assertEquals(workers - 1, refetchUrls.size)
            assertEquals(workers - 1, refetchUrls.toSet().size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun flightKeysSeparateSourcesTypesAndOrigins() {
        val key = requireNotNull(
            verificationFlightKey("source", 0, "https://example.com/book/1")
        )

        assertEquals(
            key,
            verificationFlightKey(
                "source",
                0,
                """https://example.com/book/2,{"headers":{"X-Test":"1"}}""",
            )
        )
        assertEquals(key, verificationFlightKey("source", 0, "https://example.com:443/other"))
        assertNotEquals(key, verificationFlightKey("other", 0, "https://example.com/book/1"))
        assertNotEquals(key, verificationFlightKey("source", 1, "https://example.com/book/1"))
        assertNotEquals(key, verificationFlightKey("source", 0, "https://other.com/book/1"))
        assertNotEquals(key, verificationFlightKey("source", 0, "http://example.com/book/1"))
        assertNull(verificationFlightKey("source", 0, "/book/1"))
    }

    @Test
    fun followerTimeoutAndInterruptionExitTheFlight() {
        val registry = VerificationFlightRegistry()
        val owner = registry.acquire("source")
        val follower = registry.acquire("source")

        try {
            assertThrows(NoStackTraceException::class.java) {
                registry.await(follower, 1)
            }
            assertThrows(CancellationException::class.java) {
                registry.await(follower, TimeUnit.SECONDS.toNanos(5)) { false }
            }

            Thread.currentThread().interrupt()
            try {
                assertThrows(InterruptedException::class.java) {
                    registry.await(follower, TimeUnit.SECONDS.toNanos(5))
                }
            } finally {
                Thread.interrupted()
            }
        } finally {
            registry.complete(owner)
            registry.release("source", follower)
            registry.release("source", owner)
        }
    }

    @Test
    fun completedFlightCannotBeJoinedBeforeLastRelease() {
        val registry = VerificationFlightRegistry()
        val owner = registry.acquire("source")
        val follower = registry.acquire("source")

        assertTrue(owner.owner)
        assertFalse(follower.owner)
        registry.complete(owner)
        assertSame(
            VerificationFlightWaitResult.Completed,
            registry.await(follower, TimeUnit.SECONDS.toNanos(5))
        )

        val next = registry.acquire("source")
        try {
            assertTrue(next.owner)
        } finally {
            registry.complete(next)
            registry.release("source", next)
            registry.release("source", follower)
            registry.release("source", owner)
        }
    }

    @Test
    fun abandonedOwnerLetsFollowersElectAReplacement() {
        val registry = VerificationFlightRegistry()
        val owner = registry.acquire("source")
        val follower = registry.acquire("source")

        registry.abandon(owner)
        assertSame(
            VerificationFlightWaitResult.Retry,
            registry.await(follower, TimeUnit.SECONDS.toNanos(5))
        )
        registry.release("source", follower)
        registry.release("source", owner)

        val replacement = registry.acquire("source")
        try {
            assertTrue(replacement.owner)
        } finally {
            registry.complete(replacement)
            registry.release("source", replacement)
        }
    }

    @Test
    fun repeatedCompletionKeepsActiveResultAndExpiredAttemptsCannotWrite() {
        val expiredKey = SourceVerificationHelp.registerVerificationAttempt(Thread.currentThread())
        val activeKey = SourceVerificationHelp.registerVerificationAttempt(Thread.currentThread())
        val closed = AtomicInteger()
        assertTrue(SourceVerificationHelp.attachVerificationUi(expiredKey) { closed.incrementAndGet() })
        SourceVerificationHelp.cancelVerificationAttempt(expiredKey)

        try {
            SourceVerificationHelp.setResult(expiredKey, "old-body", "old-url")
            SourceVerificationHelp.setResult(activeKey, "new-body", "new-url")
            SourceVerificationHelp.checkResult(expiredKey)
            SourceVerificationHelp.checkResult(activeKey)
            SourceVerificationHelp.checkResult(activeKey)

            assertNull(SourceVerificationHelp.getResult(expiredKey))
            assertEquals(1, closed.get())
            assertFalse(SourceVerificationHelp.attachVerificationUi(expiredKey) {})
            assertEquals(
                "new-url" to "new-body",
                SourceVerificationHelp.getResult(activeKey)
            )
        } finally {
            SourceVerificationHelp.clearResult(expiredKey)
            SourceVerificationHelp.clearResult(activeKey)
        }
    }

    @Test
    fun onlyBrowserRequestsThatCanRefetchJoinAFlight() {
        assertTrue(canJoinVerificationFlight(true, true, null))
        assertFalse(canJoinVerificationFlight(false, true, null))
        assertFalse(canJoinVerificationFlight(true, false, null))
        assertFalse(canJoinVerificationFlight(true, true, ""))
    }
}
