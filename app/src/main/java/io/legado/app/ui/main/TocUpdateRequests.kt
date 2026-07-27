package io.legado.app.ui.main

import io.legado.app.data.entities.Book
import io.legado.app.help.book.isLocal
import java.util.LinkedList

enum class TocUpdatePolicy {
    ALLOW_PRE_DOWNLOAD,
    SKIP_PRE_DOWNLOAD;

    fun merge(other: TocUpdatePolicy): TocUpdatePolicy {
        return if (this == SKIP_PRE_DOWNLOAD || other == SKIP_PRE_DOWNLOAD) {
            SKIP_PRE_DOWNLOAD
        } else {
            ALLOW_PRE_DOWNLOAD
        }
    }
}

internal fun filterBooksForTocUpdate(books: List<Book>): List<Book> {
    return books.filter { !it.isLocal && it.canUpdate }
}

internal data class TocUpdateRequestToken(
    val bookUrl: String,
    val generation: Long,
)

internal class TocUpdateRequests {

    private enum class State {
        QUEUED,
        RUNNING,
    }

    private data class Request(
        val token: TocUpdateRequestToken,
        var policy: TocUpdatePolicy,
        var refreshBookInfo: Boolean,
        var state: State,
        var decisionClosed: Boolean = false,
        var refreshDecisionClosed: Boolean = false,
        var followUpPolicy: TocUpdatePolicy? = null,
    )

    private val queue = LinkedList<TocUpdateRequestToken>()
    private val requests = hashMapOf<String, Request>()
    private var nextGeneration = 0L

    @Synchronized
    fun enqueue(
        bookUrl: String,
        policy: TocUpdatePolicy,
        refreshBookInfo: Boolean = false,
    ) {
        val current = requests[bookUrl]
        if (current != null) {
            if (!current.decisionClosed) {
                current.policy = current.policy.merge(policy)
            }
            if (refreshBookInfo && current.refreshDecisionClosed && !current.refreshBookInfo) {
                current.followUpPolicy = current.followUpPolicy?.merge(policy) ?: policy
                return
            }
            if (!current.refreshDecisionClosed) {
                current.refreshBookInfo = current.refreshBookInfo || refreshBookInfo
            }
            return
        }
        val token = TocUpdateRequestToken(bookUrl, ++nextGeneration)
        requests[bookUrl] = Request(token, policy, refreshBookInfo, State.QUEUED)
        queue.add(token)
    }

    @Synchronized
    fun poll(): TocUpdateRequestToken? {
        while (queue.isNotEmpty()) {
            val token = queue.poll() ?: continue
            val request = requests[token.bookUrl] ?: continue
            if (request.token != token) continue
            request.state = State.RUNNING
            return token
        }
        return null
    }

    @Synchronized
    fun takeRefreshBookInfo(token: TocUpdateRequestToken): Boolean {
        val request = requests[token.bookUrl]
        if (request?.token != token) return false
        request.refreshDecisionClosed = true
        return request.refreshBookInfo
    }

    /**
     * Atomically closes the pre-download decision while keeping the request running.
     * Requests arriving after this point share the current completed directory update.
     */
    @Synchronized
    fun close(token: TocUpdateRequestToken): TocUpdatePolicy {
        val request = requests[token.bookUrl]
        if (request?.token != token) return TocUpdatePolicy.SKIP_PRE_DOWNLOAD
        request.decisionClosed = true
        return request.policy
    }

    @Synchronized
    fun finish(token: TocUpdateRequestToken, persistedBookUrl: String = token.bookUrl) {
        val request = requests[token.bookUrl]
        if (request?.token == token) {
            val followUpPolicy = request.followUpPolicy
            requests.remove(token.bookUrl)
            if (followUpPolicy != null) {
                enqueue(persistedBookUrl, followUpPolicy, refreshBookInfo = true)
            }
        }
        queue.remove(token)
    }

    @Synchronized
    fun cancelAll() {
        requests.clear()
        queue.clear()
    }

    @Synchronized
    fun isRunning(bookUrl: String): Boolean {
        return requests[bookUrl]?.state == State.RUNNING
    }

    @Synchronized
    fun pendingCount(): Int = requests.size

    @Synchronized
    fun hasQueued(): Boolean {
        return requests.values.any { it.state == State.QUEUED }
    }

    @Synchronized
    fun isIdle(): Boolean = requests.isEmpty()
}
