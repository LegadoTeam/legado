package io.legado.app.model.webBook

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SearchProgressReporterTest {

    @Test
    fun reportsConcurrentCompletionsInOrder() {
        val total = 100
        val updates = mutableListOf<Pair<Int, Int>>()
        val progress = SearchProgressReporter(total) { searched, count ->
            updates.add(searched to count)
        }
        val executor = Executors.newFixedThreadPool(8)

        progress.start()
        try {
            val futures = (1..total).map {
                executor.submit { progress.completeOne() }
            }
            futures.forEach { it.get(5, TimeUnit.SECONDS) }

            assertEquals((0..total).map { it to total }, updates)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun ignoresCompletionsAfterCancellation() {
        val updates = mutableListOf<Pair<Int, Int>>()
        val progress = SearchProgressReporter(2) { searched, total ->
            updates.add(searched to total)
        }

        progress.start()
        progress.completeOne()
        progress.cancel()
        progress.completeOne()

        assertEquals(listOf(0 to 2, 1 to 2), updates)
    }

    @Test
    fun cancelledReporterCannotOverwriteReplacement() {
        val updates = mutableListOf<String>()
        val previous = SearchProgressReporter(2) { searched, total ->
            updates.add("old:$searched/$total")
        }
        val replacement = SearchProgressReporter(3) { searched, total ->
            updates.add("new:$searched/$total")
        }

        previous.start()
        previous.cancel()
        replacement.start()
        previous.completeOne()
        replacement.completeOne()

        assertEquals(listOf("old:0/2", "new:0/3", "new:1/3"), updates)
    }

    @Test
    fun finishIsOrderedAfterProgressAndStopsFurtherCallbacks() {
        val updates = mutableListOf<String>()
        val progress = SearchProgressReporter(2) { searched, total ->
            updates.add("$searched/$total")
        }

        progress.start()
        progress.completeOne()
        progress.finish { updates.add("finished") }
        progress.completeOne()
        progress.finish { updates.add("finished-again") }

        assertEquals(listOf("0/2", "1/2", "finished"), updates)
    }
}
