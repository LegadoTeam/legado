package io.legado.app.model

/** Capture play state before the gesture pauses the service; consume each gesture once. */
class ReadAloudDragSeekSession {
    enum class Direction { PENDING, VERTICAL, BLOCKED }
    data class Target(
        val chapter: Int, val paragraph: Int, val top: Float, val bottom: Float,
        val position: Int = 0, val speaking: Boolean = false,
        val screenTop: Float = top, val screenBottom: Float = bottom, val left: Float = 0f
    )
    /** Finger coordinates contribute displacement only, never an absolute seek position. */
    class RelativePosition {
        var y = 0f
            private set
        private var fingerY = 0f
        fun begin(anchorY: Float, touchY: Float) {
            y = anchorY
            fingerY = touchY
        }
        fun move(touchY: Float): Float {
            y += touchY - fingerY
            fingerY = touchY
            return y
        }
        fun rebase(newY: Float) { y = newY }
        fun edge(top: Float, bottom: Float, threshold: Float): Int = when {
            y < top - threshold -> -1
            y > bottom + threshold -> 1
            else -> 0
        }
    }
    companion object {
        fun pageTurnDirection(allowed: Boolean, dx: Float, dy: Float, slop: Int): Int =
            if (!allowed || kotlin.math.abs(dx) <= slop || kotlin.math.abs(dx) <= kotlin.math.abs(dy)) 0
            else if (dx < 0) 1 else -1

        fun readingOrder(targets: List<Target>): List<Target> {
            var offset = 0f
            var previous: Target? = null
            return targets.map { target ->
                val last = previous
                if (last != null && target.position > last.position && target.top < last.top)
                    offset += last.bottom - target.top + 12f
                previous = target
                target.copy(top = target.top + offset, bottom = target.bottom + offset)
            }
        }

        fun seekPosition(paragraphStart: Int, firstVisiblePosition: Int, crossedPage: Boolean): Int =
            if (crossedPage) maxOf(paragraphStart, firstVisiblePosition) else paragraphStart

        fun anchor(targets: List<Target>, chapter: Int, position: Int): Target? =
            targets.firstOrNull { it.speaking }
                ?: targets.lastOrNull { it.chapter == chapter && it.position <= position }
                ?: targets.firstOrNull()
        fun direction(dx: Float, dy: Float, slop: Int): Direction = when {
            kotlin.math.abs(dx) <= slop && kotlin.math.abs(dy) <= slop -> Direction.PENDING
            kotlin.math.abs(dy) > kotlin.math.abs(dx) -> Direction.VERTICAL
            else -> Direction.BLOCKED
        }

        fun targetAt(targets: List<Target>, y: Float): Target? = targets.minByOrNull {
            when {
                y < it.top -> it.top - y
                y > it.bottom -> y - it.bottom
                else -> 0f
            }
        }
    }
    enum class Action { NONE, RESUME, SEEK }
    data class Result(val action: Action, val play: Boolean = false)
    private var book: String? = null
    private var wasPlaying = false
    val isActive: Boolean get() = book != null

    fun begin(bookUrl: String, playing: Boolean): Boolean {
        if (isActive) return false
        book = bookUrl
        wasPlaying = playing
        return true
    }

    fun finish(bookUrl: String?, running: Boolean, cancelled: Boolean, targetReady: Boolean): Result {
        val originalBook = book ?: return Result(Action.NONE)
        book = null
        if (!running || originalBook != bookUrl) return Result(Action.NONE)
        return Result(if (cancelled || !targetReady) Action.RESUME else Action.SEEK, wasPlaying)
    }
}
