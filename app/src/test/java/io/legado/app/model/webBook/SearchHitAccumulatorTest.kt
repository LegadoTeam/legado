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
        assertEquals(listOf("new"), snap!!.hits.map { it.bookUrl })
        assertTrue(snap.changed)
        assertEquals(listOf("new"), acc.snapshot(2L)!!.map { it.bookUrl })
        assertNull(acc.snapshot(1L))
    }

    @Test
    fun resetDropsHitsAndRejectsOldGeneration() {
        val acc = SearchHitAccumulator()
        acc.begin(9L)
        acc.append(9L, listOf(hit("keep")))
        acc.reset()
        assertNull(acc.snapshot(9L))
        assertNull(acc.published(9L))
        assertNull(acc.append(9L, listOf(hit("after-reset"))))
        assertNull(acc.publish(9L, listOf(hit("stale-display"))))
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
        assertTrue(src.contains("rawHits.appendAndPublish("))
        assertFalse(src.contains("searchBooks ="))
        assertTrue(src.contains("SearchResultGate.accept(searchId, mSearchId)"))
        assertTrue(src.contains("onSearchSuccess(searchId, published.hits)"))
    }

    @Test
    fun staleCallbackAfterBeginDoesNotWin() {
        var currentId = 1L
        val posted = mutableListOf<List<String>>()
        fun onSuccess(searchId: Long, books: List<SearchBook>) {
            if (!SearchResultGate.accept(searchId, currentId)) return
            posted.add(books.map { it.bookUrl })
        }
        val acc = SearchHitAccumulator()
        acc.begin(1L)
        val old = acc.publish(1L, listOf(hit("old")))!!
        acc.begin(2L)
        currentId = 2L
        val new = acc.publish(2L, listOf(hit("new")))!!
        onSuccess(1L, old)
        onSuccess(2L, new)
        assertEquals(listOf(listOf("new")), posted)
        val viewModel = sequenceOf(
            File("app/src/main/java/io/legado/app/ui/book/search/SearchViewModel.kt"),
            File("src/main/java/io/legado/app/ui/book/search/SearchViewModel.kt"),
        ).first { it.isFile }.readText()
        assertTrue(viewModel.contains("searchGeneration.postIfCurrent(searchId)"))
        assertTrue(viewModel.contains("searchGeneration.beginNew"))
        assertFalse(SearchResultGate.accept(0L, 0L))
        assertFalse(SearchResultGate.accept(1L, 2L))
        assertTrue(SearchResultGate.accept(2L, 2L))
    }

    @Test
    fun staleUiPostAfterBeginNewDoesNotWin() {
        val posted = mutableListOf<String>()
        val gen = SearchUiGeneration()
        val oldId = gen.beginNew { posted.add("clear-1") }
        assertTrue(gen.postIfCurrent(oldId) { posted.add("old") })
        val newId = gen.beginNew { posted.add("clear-2") }
        assertFalse(gen.postIfCurrent(oldId) { posted.add("stale") })
        assertTrue(gen.postIfCurrent(newId) { posted.add("new") })
        assertEquals(listOf("clear-1", "old", "clear-2", "new"), posted)
    }

    @Test
    fun concurrentBeginNewDropsStalePost() {
        val gen = SearchUiGeneration()
        val oldId = gen.beginNew()
        val posted = Collections.synchronizedList(mutableListOf<String>())
        val start = CyclicBarrier(2)
        val done = CountDownLatch(2)
        Thread {
            try {
                start.await(5, TimeUnit.SECONDS)
                gen.postIfCurrent(oldId) { posted.add("old") }
            } finally {
                done.countDown()
            }
        }.start()
        Thread {
            try {
                start.await(5, TimeUnit.SECONDS)
                val newId = gen.beginNew()
                gen.postIfCurrent(newId) { posted.add("new") }
            } finally {
                done.countDown()
            }
        }.start()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertTrue(posted.contains("new"))
        assertTrue(posted.last() == "new")
        assertFalse(posted == listOf("new", "old"))
    }

    @Test
    fun stalePublishDoesNotReplaceNewerDisplay() {
        val acc = SearchHitAccumulator()
        acc.begin(1L)
        acc.publish(1L, listOf(hit("old")))
        acc.begin(2L)
        acc.publish(2L, listOf(hit("new")))
        assertNull(acc.publish(1L, listOf(hit("stale"))))
        assertEquals(listOf("new"), acc.published(2L)!!.map { it.bookUrl })
        assertNull(acc.published(1L))
    }

    @Test
    fun concurrentStalePublishDoesNotReplaceNewDisplay() {
        val acc = SearchHitAccumulator()
        acc.begin(1L)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val start = CyclicBarrier(2)
        val done = CountDownLatch(2)
        Thread {
            try {
                start.await(5, TimeUnit.SECONDS)
                acc.publish(1L, listOf(hit("old")))
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
                acc.publish(2L, listOf(hit("new")))
            } catch (t: Throwable) {
                errors.add(t)
            } finally {
                done.countDown()
            }
        }.start()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals(listOf("new"), acc.published(2L)!!.map { it.bookUrl })
        assertNull(acc.published(1L))
    }

    @Test
    fun appendAndPublishKeepsNewerDisplayWhenOlderBuildFinishesLast() {
        val acc = SearchHitAccumulator()
        acc.begin(1L)
        val first = acc.appendAndPublish(1L, listOf(hit("A"))) { it }
        assertTrue(first!!.changed)
        assertEquals(listOf("A"), first.hits.map { it.bookUrl })
        val dup = acc.appendAndPublish(1L, listOf(hit("A"))) { error("should not rebuild") }
        assertFalse(dup!!.changed)
        assertEquals(listOf("A"), dup.hits.map { it.bookUrl })
        val extra = acc.appendAndPublish(1L, listOf(hit("B"))) { books ->
            books.filter { it.bookUrl == "B" }
        }
        assertTrue(extra!!.changed)
        assertEquals(listOf("B"), extra.hits.map { it.bookUrl })
        assertEquals(listOf("B"), acc.published(1L)!!.map { it.bookUrl })
    }

    @Test
    fun duplicatePagesDoNotGrowRawHits() {
        val acc = SearchHitAccumulator()
        acc.begin(1L)
        val first = acc.append(1L, listOf(hit("A"), hit("B")))
        assertTrue(first!!.changed)
        assertEquals(listOf("A", "B"), first.hits.map { it.bookUrl })
        val dup = acc.append(1L, listOf(hit("A"), hit("B")))
        assertFalse(dup!!.changed)
        assertEquals(listOf("A", "B"), dup.hits.map { it.bookUrl })
        val empty = acc.append(1L, emptyList())
        assertFalse(empty!!.changed)
        assertEquals(listOf("A", "B"), empty.hits.map { it.bookUrl })
        val extra = acc.append(1L, listOf(hit("B"), hit("C")))
        assertTrue(extra!!.changed)
        assertEquals(listOf("A", "B", "C"), extra.hits.map { it.bookUrl })
    }

    @Test
    fun searchModelSkipsRebuildWhenHitsUnchanged() {
        val src = sequenceOf(
            File("app/src/main/java/io/legado/app/model/webBook/SearchModel.kt"),
            File("src/main/java/io/legado/app/model/webBook/SearchModel.kt"),
        ).first { it.isFile }.readText()
        assertTrue(src.contains("appendAndPublish"))
        assertTrue(src.contains("hasMore = hasMore || published.changed"))
        assertTrue(src.contains("rawHits.published(searchId)"))
        val startSearch = src.substringAfter("private fun startSearch()").substringBefore("private suspend fun mergeItems")
        val completion = startSearch.substringAfter("onCompletion").substringBefore("activeProgress")
        assertTrue(completion.contains("rawHits.published(searchId)"))
        assertFalse(completion.contains("rebuildDisplay"))
        assertFalse(completion.contains("onSearchSuccess"))
        assertFalse(src.contains("fun rebuildDisplay"))
        assertTrue(startSearch.contains("searchJob?.cancel()"))
        assertFalse(startSearch.contains("acceptFinish"))
        assertFalse(startSearch.contains("CoroutineStart.LAZY"))
        val viewModel = sequenceOf(
            File("app/src/main/java/io/legado/app/ui/book/search/SearchViewModel.kt"),
            File("src/main/java/io/legado/app/ui/book/search/SearchViewModel.kt"),
        ).first { it.isFile }.readText()
        assertTrue(viewModel.contains("searchStartLock"))
        assertTrue(viewModel.contains("synchronized(searchStartLock)"))
        val searchFn = viewModel.substringAfter("fun search(key: String)").substringBefore("fun stop()")
        assertTrue(searchFn.contains("searchModel.cancelSearch()"))
        assertTrue(searchFn.contains("searchGeneration.beginNew"))
        assertTrue(searchFn.contains("searchModel.search("))
        assertTrue(searchFn.contains("synchronized(searchStartLock)"))
        assertTrue(searchFn.contains("start.first != searchGeneration.current"))
        assertTrue(searchFn.contains("execute {"))
        val bump = searchFn.substringAfter("synchronized(searchStartLock)").substringBefore("execute {")
        assertTrue(bump.contains("searchModel.cancelSearch()"))
        assertTrue(bump.contains("searchGeneration.beginNew"))
        assertFalse(bump.contains("searchModel.search("))
    }

    private fun hit(bookUrl: String) = SearchBook(
        bookUrl = bookUrl,
        origin = "origin",
        originName = "Source",
        name = bookUrl,
        author = "A",
    )
}
