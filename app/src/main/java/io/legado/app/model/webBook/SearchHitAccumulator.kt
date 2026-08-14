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
