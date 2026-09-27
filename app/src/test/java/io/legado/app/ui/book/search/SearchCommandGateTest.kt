package io.legado.app.ui.book.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SearchCommandGateTest {

    @Test
    fun stopInvalidatesQueuedCommandAndWaitsForRunningCommand() {
        val gate = SearchCommandGate()
        val runningCommand = gate.next()
        val actionStarted = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val stopStarted = CountDownLatch(1)
        val stopFinished = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val running = executor.submit<Boolean> {
                gate.runIfCurrent(runningCommand) {
                    actionStarted.countDown()
                    releaseAction.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue(actionStarted.await(5, TimeUnit.SECONDS))

            val stop = executor.submit {
                stopStarted.countDown()
                gate.invalidate { stopFinished.countDown() }
            }
            assertTrue(stopStarted.await(5, TimeUnit.SECONDS))
            assertFalse(stopFinished.await(100, TimeUnit.MILLISECONDS))
            releaseAction.countDown()
            assertTrue(running.get(5, TimeUnit.SECONDS))
            stop.get(5, TimeUnit.SECONDS)
            assertTrue(stopFinished.await(5, TimeUnit.SECONDS))

            var queuedCommandRan = false
            assertFalse(gate.runIfCurrent(runningCommand) { queuedCommandRan = true })
            assertFalse(queuedCommandRan)
        } finally {
            releaseAction.countDown()
            executor.shutdownNow()
        }
    }
}
