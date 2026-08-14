package io.legado.app.model.webBook

import io.legado.app.data.entities.SearchBook

/**
 * Per-search raw hits. Replaces a shared mutable ArrayList so cancel/restart
 * cannot mix generations or throw ConcurrentModificationException.
 */
internal class SearchHitAccumulator {
    private val lock = Any()
    private var generation = 0L
    private var hits: List<SearchBook> = emptyList()
    private var display: List<SearchBook> = emptyList()

    fun begin(generation: Long) {
        synchronized(lock) {
            this.generation = generation
            hits = emptyList()
            display = emptyList()
        }
    }

    fun reset() {
        begin(0L)
    }

    fun append(generation: Long, items: List<SearchBook>): List<SearchBook>? {
        synchronized(lock) {
            if (generation == 0L || generation != this.generation) return null
            if (items.isNotEmpty()) {
                hits = hits + items
            }
            return hits
        }
    }

    fun snapshot(generation: Long): List<SearchBook>? {
        synchronized(lock) {
            if (generation == 0L || generation != this.generation) return null
            return hits
        }
    }

    /**
     * Atomically accept [books] as the visible list for [generation].
     * Returns the submitted list, or null if this search is no longer current.
     */
    fun publish(generation: Long, books: List<SearchBook>): List<SearchBook>? {
        synchronized(lock) {
            if (generation == 0L || generation != this.generation) return null
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

/** Drop a search callback whose id is no longer the UI's current search. */
internal object SearchResultGate {
    fun accept(submittedId: Long, currentId: Long): Boolean {
        return submittedId != 0L && submittedId == currentId
    }
}

/** UI search id + LiveData post share one lock so a stale callback cannot win. */
internal class SearchUiGeneration {
    private val lock = Any()

    @Volatile
    var current = 0L
        private set

    fun beginNew(onBegin: () -> Unit = {}): Long {
        synchronized(lock) {
            current += 1L
            if (current == 0L) current = 1L
            onBegin()
            return current
        }
    }

    fun begin(id: Long, onBegin: () -> Unit = {}): Long {
        synchronized(lock) {
            current = id
            onBegin()
            return current
        }
    }

    fun invalidate() {
        synchronized(lock) {
            current = 0L
        }
    }

    fun postIfCurrent(submittedId: Long, post: () -> Unit): Boolean {
        synchronized(lock) {
            if (!SearchResultGate.accept(submittedId, current)) return false
            post()
            return true
        }
    }
}
