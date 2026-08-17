package io.legado.app.model.webBook

import io.legado.app.data.entities.SearchBook

/**
 * Per-search raw hits. Replaces a shared mutable ArrayList so cancel/restart
 * cannot mix generations or throw ConcurrentModificationException.
 *
 * Same-generation publishes carry the hits [revision] from append. A slower
 * rebuild from an older revision cannot overwrite a newer display.
 */
internal class SearchHitAccumulator {
    private val lock = Any()
    private var generation = 0L
    private var hitsRevision = 0L
    private var hits: List<SearchBook> = emptyList()
    private var display: List<SearchBook> = emptyList()

    fun begin(generation: Long) {
        synchronized(lock) {
            this.generation = generation
            hitsRevision = 0L
            hits = emptyList()
            display = emptyList()
        }
    }

    fun reset() {
        begin(0L)
    }

    fun append(generation: Long, items: List<SearchBook>): SearchHitAppend? {
        synchronized(lock) {
            return appendLocked(generation, items)
        }
    }

    private fun appendLocked(generation: Long, items: List<SearchBook>): SearchHitAppend? {
        if (generation == 0L || generation != this.generation) return null
        if (items.isEmpty()) {
            return SearchHitAppend(hits, changed = false, revision = hitsRevision)
        }
        val seen = HashSet<String>(hits.size + items.size)
        hits.forEach { seen.add(it.primaryStr()) }
        val added = ArrayList<SearchBook>(items.size)
        for (item in items) {
            if (seen.add(item.primaryStr())) {
                added.add(item)
            }
        }
        if (added.isEmpty()) {
            return SearchHitAppend(hits, changed = false, revision = hitsRevision)
        }
        hits = hits + added
        hitsRevision += 1
        return SearchHitAppend(hits, changed = true, revision = hitsRevision)
    }

    fun snapshot(generation: Long): List<SearchBook>? {
        synchronized(lock) {
            if (generation == 0L || generation != this.generation) return null
            return hits
        }
    }

    /**
     * Atomically accept [books] as the visible list for [generation] when
     * [revision] still matches the current hits revision.
     */
    fun publish(
        generation: Long,
        revision: Long,
        books: List<SearchBook>,
    ): List<SearchBook>? {
        synchronized(lock) {
            if (generation == 0L || generation != this.generation) return null
            if (revision != hitsRevision) return null
            display = books
            return display
        }
    }

    fun published(generation: Long): List<SearchBook>? {
        synchronized(lock) {
            if (generation == 0L || generation != this.generation) return null
            return display
        }
    }
}

internal data class SearchHitAppend(
    val hits: List<SearchBook>,
    val changed: Boolean,
    val revision: Long = 0L,
)

/** Drop a search callback whose id/revision is no longer the UI's current search. */
internal object SearchResultGate {
    fun accept(submittedId: Long, currentId: Long): Boolean {
        return submittedId != 0L && submittedId == currentId
    }

    fun accept(
        submittedId: Long,
        currentId: Long,
        submittedRevision: Long,
        lastAcceptedRevision: Long,
    ): Boolean {
        if (!accept(submittedId, currentId)) return false
        return submittedRevision >= lastAcceptedRevision
    }
}

/**
 * Single synchronized publish point for search UI: generation + revision + post.
 * stop() must invalidate the generation so late callbacks cannot reuse the id.
 */
internal class SearchUiPublishGate {
    private val lock = Any()
    private var currentId = 0L
    private var acceptedRevision = 0L

    fun begin(searchId: Long) {
        synchronized(lock) {
            currentId = searchId
            acceptedRevision = 0L
        }
    }

    /** Reject all callbacks until the next [begin]. */
    fun invalidate() {
        synchronized(lock) {
            currentId = 0L
            acceptedRevision = 0L
        }
    }

    fun isActive(searchId: Long): Boolean {
        synchronized(lock) {
            return currentId != 0L && currentId == searchId
        }
    }

    fun publish(
        searchId: Long,
        revision: Long,
        books: List<SearchBook>,
        post: (List<SearchBook>) -> Unit,
    ): Boolean {
        synchronized(lock) {
            if (!SearchResultGate.accept(searchId, currentId, revision, acceptedRevision)) {
                return false
            }
            acceptedRevision = revision
            post(books)
            return true
        }
    }
}
