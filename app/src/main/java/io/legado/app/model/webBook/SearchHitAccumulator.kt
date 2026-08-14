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

    fun begin(generation: Long) {
        synchronized(lock) {
            this.generation = generation
            hits = emptyList()
        }
    }

    fun reset() {
        begin(0L)
    }

    fun isCurrent(generation: Long): Boolean {
        synchronized(lock) {
            return generation != 0L && generation == this.generation
        }
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
}
