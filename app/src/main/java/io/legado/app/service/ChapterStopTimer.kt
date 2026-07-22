package io.legado.app.service

internal const val MAX_CHAPTER_STOP_COUNT = 99

internal data class ChapterStopResult(
    val remaining: Int,
    val shouldStop: Boolean,
)

internal class ChapterStopTimer(initialCount: Int = 0) {

    var remaining: Int = normalizeChapterStopCount(initialCount)
        private set

    fun set(count: Int): Int {
        remaining = normalizeChapterStopCount(count)
        return remaining
    }

    fun clear() {
        remaining = 0
    }

    fun onChapterCompleted(): ChapterStopResult? {
        if (remaining <= 0) return null
        remaining--
        return ChapterStopResult(remaining, remaining == 0)
    }
}

internal fun normalizeChapterStopCount(count: Int): Int {
    return count.coerceIn(0, MAX_CHAPTER_STOP_COUNT)
}
