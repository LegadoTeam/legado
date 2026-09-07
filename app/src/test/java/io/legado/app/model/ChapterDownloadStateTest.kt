package io.legado.app.model

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ChapterDownloadStateTest {
    @Test
    fun `cancelled prefetch restores only borrowed manual requests`() {
        val state = ChapterDownloadState()
        state.enqueue(listOf(1, 2))
        val batch = state.claimBatch(listOf(1, 2, 3), manual = false)
        assertEquals(0, state.waitCount)
        batch.forEach { state.finish(it) }
        assertEquals(listOf(1, 2), state.waitingIndexes())
        assertEquals(0, state.runningCount)
    }

    @Test
    fun `declining a one chapter batch restores its manual request`() {
        val state = ChapterDownloadState()
        state.enqueue(listOf(1))
        val other = state.claimRead(2).first
        val batch = state.claimBatch(listOf(1, 2), manual = false)
        assertEquals(1, batch.size)
        batch.forEach { state.finish(it) }
        assertEquals(listOf(1), state.waitingIndexes())
        assertSame(other, state.claimRead(2).first)
    }

    @Test
    fun `a manual request added during reading survives reader cancellation`() {
        val state = ChapterDownloadState()
        val read = state.claimRead(1).first
        state.enqueue(listOf(1))
        assertTrue(state.hasManualWork)
        state.finish(read)
        assertEquals(listOf(1), state.waitingIndexes())
    }

    @Test
    fun `reader text success retains manual work until images are cached`() {
        val state = ChapterDownloadState()
        val read = state.claimRead(1).first
        state.enqueue(listOf(1))
        state.finish(read, Result.success("text with images"), manualComplete = false)
        assertEquals(listOf(1), state.waitingIndexes())
        state.finish(state.claimManual(1)!!, Result.success("text with images"))
        assertTrue(state.isIdle)
    }

    @Test
    fun `stopping clears borrowed intent but permits a later new request`() {
        val state = ChapterDownloadState()
        state.enqueue(listOf(1, 2))
        val batch = state.claimBatch(listOf(1, 2), manual = false)
        state.stopManual()
        state.enqueue(listOf(2))
        batch.forEach { state.finish(it) }
        assertEquals(listOf(2), state.waitingIndexes())
    }

    @Test
    fun `late cleanup never releases a replacement owner`() {
        val state = ChapterDownloadState()
        val old = state.claimRead(1).first
        assertTrue(state.finish(old))
        val current = state.claimRead(1).first
        assertFalse(state.finish(old, fallback = true))
        assertSame(current, state.claimRead(1).first)
        assertFalse(current.result.isCompleted)
        assertTrue(state.canBatch(1))
    }

    @Test
    fun `partial batch success is not returned or marked for fallback`() = runBlocking {
        val state = ChapterDownloadState()
        state.enqueue(listOf(1, 2))
        val batch = state.claimBatch(listOf(1, 2), manual = true)
        state.finish(batch[0], Result.success("first"))
        state.finish(batch[1], fallback = true)
        batch.forEach { state.finish(it, fallback = true) }
        assertEquals(listOf(2), state.waitingIndexes())
        assertEquals("first", batch[0].result.await()!!.getOrThrow())
        assertTrue(state.canBatch(1))
        assertFalse(state.canBatch(2))
        assertTrue(state.claimBatch(listOf(2), manual = false).isEmpty())
        assertNotNull(state.claimManual(2))
    }

    @Test
    fun `pure reader cancellation and failure never create explicit downloads`() {
        val state = ChapterDownloadState()
        state.finish(state.claimRead(1).first)
        state.finish(state.claimRead(2).first, Result.failure(IllegalStateException("offline")))
        assertTrue(state.isIdle)
        assertFalse(state.hasManualWork)
    }

    @Test
    fun `terminal manual failure does not retry`() {
        val state = ChapterDownloadState()
        state.enqueue(listOf(1))
        state.finish(state.claimManual(1)!!, Result.failure(IllegalStateException("offline")), retryManual = false)
        assertTrue(state.isIdle)
    }

    @Test
    fun `overlapping reads share one owner and cancelling a waiter preserves its result`() = runBlocking {
        withTimeout(5000) {
            val state = ChapterDownloadState()
            val claims = (1..24).map { async(Dispatchers.Default) { state.claimRead(1) } }.awaitAll()
            assertEquals(1, claims.count { it.second })
            val owner = claims.first().first
            assertTrue(claims.all { it.first === owner })
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) { owner.result.await() }
            val reader = async(start = CoroutineStart.UNDISPATCHED) { owner.result.await() }
            cancelled.cancelAndJoin()
            assertFalse(owner.result.isCancelled)
            state.finish(owner, Result.success("shared"))
            assertEquals("shared", reader.await()!!.getOrThrow())
            assertTrue(state.isIdle)
        }
    }

    @Test
    fun `batch cannot claim a running read or the same index twice`() {
        val state = ChapterDownloadState()
        val reader = state.claimRead(2).first
        val batch = state.claimBatch(listOf(1, 1, 2, 3), manual = false)
        assertEquals(listOf(1, 3), batch.map { it.index })
        batch.forEach { state.finish(it) }
        assertSame(reader, state.claimRead(2).first)
    }
}
