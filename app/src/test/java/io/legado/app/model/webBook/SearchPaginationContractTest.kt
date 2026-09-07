package io.legado.app.model.webBook

import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class SearchPaginationContractTest {

    @Test
    fun ownerRejectsDuplicatesAndLateCompletion() {
        val owner = SearchPageOwner()
        val first = Job()
        val second = Job()
        val third = Job()

        assertFalse(owner.complete(null) {})
        assertTrue(owner.register(first))
        assertFalse(owner.register(second))
        assertTrue(owner.complete(first) {})
        assertTrue(owner.register(second))
        assertFalse(owner.complete(first) {})
        assertFalse(owner.register(third))
        assertSame(second, owner.cancel())
    }

    @Test
    fun terminalCallbackFinishesBeforeNextPageCanStart() {
        val owner = SearchPageOwner()
        val first = Job()
        val second = Job()
        val callbackStarted = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val replacementStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        assertTrue(owner.register(first))
        try {
            val completion = executor.submit<Boolean> {
                owner.complete(first) {
                    callbackStarted.countDown()
                    releaseCallback.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue(callbackStarted.await(5, TimeUnit.SECONDS))
            val replacement = executor.submit<Boolean> {
                replacementStarted.countDown()
                owner.register(second)
            }

            assertTrue(replacementStarted.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) {
                replacement.get(100, TimeUnit.MILLISECONDS)
            }
            releaseCallback.countDown()
            assertTrue(completion.get(5, TimeUnit.SECONDS))
            assertTrue(replacement.get(5, TimeUnit.SECONDS))
            assertSame(second, owner.cancel())
        } finally {
            releaseCallback.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun searchModelUsesOwnerBeforeIncrementAndJobStart() {
        val source = projectFile(
            "src/main/java/io/legado/app/model/webBook/SearchModel.kt"
        ).readText()
        val search = source.section(
            "fun search(searchId: Long, key: String)",
            "private fun startSearch()"
        )
        val launch = source.section("private fun startSearch()", "private suspend fun mergeItems")

        assertTrue(search.contains("synchronized(pageOwner)"))
        assertTrue(
            search.indexOf("pageOwner.isRunning()") in 0..<search.indexOf("searchPage++")
        )
        assertTrue(
            launch.indexOf("CoroutineStart.LAZY") in 0..<launch.indexOf("pageOwner.register(job)")
        )
        assertTrue(launch.indexOf("pageOwner.register(job)") < launch.indexOf("job.start()"))
        assertTrue(launch.contains("pageOwner.complete(context[Job])"))
        assertTrue(
            source.section("fun close()", "interface CallBack")
                .contains("pageOwner.cancel()?.cancel()")
        )
    }

    private fun String.section(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        val end = indexOf(endMarker, start + startMarker.length)
        assertTrue("Missing section start: $startMarker", start >= 0)
        assertTrue("Missing section end: $endMarker", end > start)
        return substring(start, end)
    }

    private fun projectFile(pathInApp: String): File =
        listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?: error("Missing project file: $pathInApp")
}
