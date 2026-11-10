package io.legado.app.ui.book.read.page

import io.legado.app.ui.book.read.page.entities.PageDirection
import kotlin.math.abs

/** Retain multi-touch consumption through the final UP, even without a MOVE event. */
internal class NativePageTurnGesture {
    var consumed: Boolean = false
        private set
    private var canceled = false

    fun begin() {
        consumed = false
        canceled = false
    }

    fun cancel() {
        consumed = true
        canceled = true
    }

    /** A hardware/automatic turn must consume an already-held finger through its final UP. */
    fun finish(touching: Boolean) {
        if (touching) cancel() else begin()
    }

    fun direction(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        slopSquare: Int,
    ): PageDirection {
        if (canceled) return PageDirection.NONE
        val direction = nativePageTurnDirection(startX, startY, endX, endY, slopSquare)
        if (direction != PageDirection.NONE) consumed = true
        return direction
    }
}

/** Instant page turns use the whole horizontal gesture, not the last MOVE's velocity. */
internal fun nativePageTurnDirection(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    slopSquare: Int,
): PageDirection {
    val deltaX = endX - startX
    val deltaY = endY - startY
    if (deltaX * deltaX <= slopSquare || abs(deltaX) <= abs(deltaY)) {
        return PageDirection.NONE
    }
    return if (deltaX < 0f) PageDirection.NEXT else PageDirection.PREV
}
