package io.legado.app.model.webBook

import io.legado.app.data.entities.SearchBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

class SearchHitAccumulatorTest {

    @Test
    fun staleGenerationAppendDoesNotMixIntoNewSearch() {
        val acc = SearchHitAccumulator()
        acc.begin(1L)
        acc.append(1L, listOf(hit("old")))
        acc.begin(2L)
        assertNull(acc.append(1L, listOf(hit("stale"))))
        val snap = acc.append(2L, listOf(hit("new")))
        assertEquals(listOf("new"), snap!!.map { it.bookUrl })
        assertEquals(listOf("new"), acc.snapshot(2L)!!.map { it.bookUrl })
        assertNull(acc.snapshot(1L))
    }

    @Test
    fun resetDropsHitsAndRejectsOldGeneration() {
        val acc = SearchHitAccumulator()
        acc.begin(9L)
        acc.append(9L, listOf(hit("keep")))
        acc.reset()
        assertFalse(acc.isCurrent(9L))
        assertNull(acc.append(9L, listOf(hit("after-reset"))))
        assertNull(acc.snapshot(9L))
    }

    @Test
    fun concurrentCancelRestartDoesNotThrowOrMix() {
        val acc = SearchHitAccumulator()
        acc.begin(1L)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val start = CyclicBarrier(2)
        val done = CountDownLatch(2)
        Thread {
            try {
                start.await(5, TimeUnit.SECONDS)
                repeat(300) {
                    acc.append(1L, listOf(hit("g1-$it")))
                }
            } catch (t: Throwable) {
                errors.add(t)
            } finally {
                done.countDown()
            }
        }.start()
        Thread {
            try {
                start.await(5, TimeUnit.SECONDS)
                acc.begin(2L)
                repeat(300) {
                    acc.append(2L, listOf(hit("g2-$it")))
                }
            } catch (t: Throwable) {
                errors.add(t)
            } finally {
                done.countDown()
            }
        }.start()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertTrue(errors.toString(), errors.isEmpty())
        val snap = acc.snapshot(2L)
        assertTrue(snap != null)
        assertTrue(snap!!.none { it.bookUrl.startsWith("g1-") })
        assertTrue(snap.all { it.bookUrl.startsWith("g2-") })
    }

    @Test
    fun searchModelReplacesSharedArrayListWithAccumulator() {
        val src = sequenceOf(
            File("app/src/main/java/io/legado/app/model/webBook/SearchModel.kt"),
            File("src/main/java/io/legado/app/model/webBook/SearchModel.kt"),
        ).first { it.isFile }.readText()
        assertTrue(src.contains("SearchHitAccumulator"))
        assertFalse(src.contains("rawSearchHits"))
    }

    private fun hit(bookUrl: String) = SearchBook(
        bookUrl = bookUrl,
        origin = "origin",
        originName = "Source",
        name = bookUrl,
        author = "A",
    )
}
