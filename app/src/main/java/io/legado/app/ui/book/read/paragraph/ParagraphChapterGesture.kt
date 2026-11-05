package io.legado.app.ui.book.read.paragraph

import kotlin.math.abs

/** A fresh outward pull from an existing chapter boundary, completed only on release. */
internal class ParagraphChapterGesture(private val touchSlop: Float) {
    private val triggerDistance = touchSlop * 6f
    private var active = false
    private var startX = 0f
    private var startY = 0f
    private var previousAllowed = false
    private var nextAllowed = false

    fun start(x: Float, y: Float, canGoPrevious: Boolean, canGoNext: Boolean) {
        cancel()
        active = canGoPrevious || canGoNext
        startX = x
        startY = y
        previousAllowed = canGoPrevious
        nextAllowed = canGoNext
    }

    /** True once the list should hand the outward drag to this gesture. */
    fun move(x: Float, y: Float): Boolean {
        if (!active) return false
        val dx = abs(x - startX)
        val dy = y - startY
        if (dx > touchSlop && dx >= abs(dy)
            || dy > touchSlop && !previousAllowed
            || dy < -touchSlop && !nextAllowed
        ) {
            cancel()
            return false
        }
        return abs(dy) > triggerDistance && abs(dy) > dx
    }

    /** Null means an ordinary/cancelled drag; true means the next chapter. */
    fun release(x: Float, y: Float): Boolean? {
        val direction = if (move(x, y)) y < startY else null
        cancel()
        return direction
    }

    fun cancel() {
        active = false
        previousAllowed = false
        nextAllowed = false
    }
}
