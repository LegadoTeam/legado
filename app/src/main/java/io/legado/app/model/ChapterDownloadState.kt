package io.legado.app.model

import kotlinx.coroutines.CompletableDeferred

/** One owner per chapter; a manual cache request survives a reader borrowing its work. */
internal class ChapterDownloadState {
    class Ticket internal constructor(val index: Int, internal var manualRequested: Boolean) {
        // null means that the owner stopped or a batch missed this chapter: callers may retry.
        val result = CompletableDeferred<Result<String>?>()
    }

    private val waiting = linkedSetOf<Int>()
    private val running = linkedMapOf<Int, Ticket>()
    private val batchFallback = hashSetOf<Int>()
    private val resourceRefresh = hashSetOf<Int>()

    val waitCount get() = synchronized(this) { waiting.size }
    val runningCount get() = synchronized(this) { running.size }
    val isIdle get() = synchronized(this) { waiting.isEmpty() && running.isEmpty() }
    val hasManualWork get() = synchronized(this) {
        waiting.isNotEmpty() || running.values.any { it.manualRequested }
    }

    @Synchronized
    fun enqueue(indexes: Iterable<Int>, refreshResources: Boolean = false) {
        indexes.forEach { index ->
            if (refreshResources) resourceRefresh.add(index)
            val ticket = running[index]
            if (ticket == null) waiting.add(index) else ticket.manualRequested = true
        }
    }

    @Synchronized
    fun stopManual() {
        waiting.clear()
        batchFallback.clear()
        resourceRefresh.clear()
        running.values.forEach { it.manualRequested = false }
    }

    @Synchronized
    fun waitingIndexes(): List<Int> = waiting.toList()

    /** Refresh only these chapters; preserve explicit caching and release readers to retry. */
    fun invalidate(indexes: Iterable<Int>) {
        val obsolete = synchronized(this) {
            indexes.mapNotNull { index ->
                batchFallback.remove(index)
                running.remove(index)?.also { if (it.manualRequested) waiting.add(index) }
            }
        }
        obsolete.forEach { it.result.complete(null) }
    }

    @Synchronized
    fun discardWaiting(index: Int) {
        waiting.remove(index)
        resourceRefresh.remove(index)
    }

    @Synchronized
    fun requestsResourceRefresh(index: Int): Boolean = index in resourceRefresh

    @Synchronized
    fun isCurrent(ticket: Ticket): Boolean = running[ticket.index] === ticket

    @Synchronized
    fun canBatch(index: Int): Boolean = index !in batchFallback

    @Synchronized
    fun claimManual(index: Int): Ticket? {
        if (index !in waiting || index in running) return null
        return begin(index)
    }

    /** Atomic join-or-create, shared by ordinary reading and read-aloud. */
    @Synchronized
    fun claimRead(index: Int): Pair<Ticket, Boolean> {
        running[index]?.let { return it to false }
        return begin(index) to true
    }

    @Synchronized
    fun claimBatch(indexes: List<Int>, manual: Boolean): List<Ticket> =
        indexes.mapNotNull { index ->
            if (index in running || index in batchFallback || (manual && index !in waiting)) null else begin(index)
        }

    private fun begin(index: Int): Ticket = Ticket(index, waiting.remove(index)).also {
        running[index] = it
    }

    /** A late callback cannot release a newer owner or restore a stopped manual request. */
    fun finish(
        ticket: Ticket,
        result: Result<String>? = null,
        retryManual: Boolean = true,
        fallback: Boolean = false,
        manualComplete: Boolean = true,
        onFinished: () -> Unit = {},
    ): Boolean {
        synchronized(this) {
            if (running[ticket.index] !== ticket) return false
            onFinished()
            running.remove(ticket.index)
            if (result?.isSuccess == true) {
                batchFallback.remove(ticket.index)
                // A reader fetched text; explicit caching still needs to download its images.
                if (ticket.manualRequested && !manualComplete) waiting.add(ticket.index)
                else resourceRefresh.remove(ticket.index)
            } else {
                if (retryManual && ticket.manualRequested) waiting.add(ticket.index)
                else resourceRefresh.remove(ticket.index)
                if (fallback) batchFallback.add(ticket.index)
            }
        }
        ticket.result.complete(result)
        return true
    }
}
