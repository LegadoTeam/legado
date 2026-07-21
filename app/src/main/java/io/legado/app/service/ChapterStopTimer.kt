package io.legado.app.service

internal const val MAX_CHAPTER_STOP_COUNT = 99

internal data class ChapterStopResult(
    val remaining: Int,
    val shouldStop: Boolean,
)

internal data class SleepTimerIncrement(
    val minute: Int = 0,
    val chapter: Int = 0,
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

internal fun nextSleepTimerIncrement(
    timeMinute: Int,
    chapterToStop: Int,
    preferChapter: Boolean,
): SleepTimerIncrement {
    val minute = timeMinute.coerceIn(0, 180)
    val chapter = normalizeChapterStopCount(chapterToStop)
    return when {
        chapter > 0 -> SleepTimerIncrement(
            chapter = (chapter + 1).coerceAtMost(MAX_CHAPTER_STOP_COUNT)
        )

        minute == 0 && preferChapter -> SleepTimerIncrement(chapter = 1)
        minute == 180 -> SleepTimerIncrement()
        else -> SleepTimerIncrement(minute = (minute + 10).coerceAtMost(180))
    }
}
