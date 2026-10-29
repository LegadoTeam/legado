package io.legado.app.service

internal const val NEXT_CHAPTER_TTS_PREFETCH_ITEM_LIMIT = 10

/** One event-driven attempt per target; waiting never schedules timers or retries. */
internal class ParagraphPrefetchState<K : Any> {
    private var target: K? = null
    private var attempted = false
    private var loading = false

    fun arm(key: K) {
        if (target == key) return
        target = key
        attempted = false
        loading = false
    }

    fun claimAttempt(key: K): Boolean {
        if (target != key || attempted) return false
        attempted = true
        return true
    }

    fun claimLoad(key: K): Boolean {
        if (target != key || attempted || loading) return false
        loading = true
        return true
    }

    fun isPending(key: K): Boolean = target == key && !attempted

    fun clear() {
        target = null
        attempted = false
        loading = false
    }
}
