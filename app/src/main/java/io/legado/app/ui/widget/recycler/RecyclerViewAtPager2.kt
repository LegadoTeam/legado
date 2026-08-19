package io.legado.app.ui.widget.recycler

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

private const val HORIZONTAL_SWIPE_THRESHOLD = 50

class RecyclerViewAtPager2 : RecyclerView {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    private var startX = 0
    private var startY = 0
    private var capturedSwipeDirection = 0

    /** Receives -1 for a right swipe and 1 for a left swipe. */
    var canHandleHorizontalSwipe: ((Int) -> Boolean)? = null
    var onHorizontalSwipe: ((Int) -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x.toInt()
                startY = ev.y.toInt()
                capturedSwipeDirection = 0
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val endX = ev.x.toInt()
                val endY = ev.y.toInt()
                val direction = horizontalSwipeDirection(
                    startX,
                    startY,
                    endX,
                    endY,
                    HORIZONTAL_SWIPE_THRESHOLD,
                )
                if (capturedSwipeDirection != 0) {
                    parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                if (direction != 0) {
                    if (canHandleHorizontalSwipe?.invoke(direction) == true) {
                        capturedSwipeDirection = direction
                        parent.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.obtain(ev).let { cancelEvent ->
                            cancelEvent.action = MotionEvent.ACTION_CANCEL
                            super.dispatchTouchEvent(cancelEvent)
                            cancelEvent.recycle()
                        }
                        return true
                    }
                    parent.requestDisallowInterceptTouchEvent(false)
                } else {
                    parent.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP -> {
                parent.requestDisallowInterceptTouchEvent(false)
                if (capturedSwipeDirection != 0) {
                    val direction = horizontalSwipeDirection(
                        startX,
                        startY,
                        ev.x.toInt(),
                        ev.y.toInt(),
                        HORIZONTAL_SWIPE_THRESHOLD,
                    )
                    if (direction == capturedSwipeDirection &&
                        canHandleHorizontalSwipe?.invoke(capturedSwipeDirection) == true
                    ) {
                        onHorizontalSwipe?.invoke(capturedSwipeDirection)
                    }
                    capturedSwipeDirection = 0
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                if (capturedSwipeDirection != 0) {
                    capturedSwipeDirection = 0
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

}

internal fun horizontalSwipeDirection(
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
    touchSlop: Int,
): Int {
    val deltaX = endX - startX
    val deltaY = endY - startY
    if (abs(deltaX) <= touchSlop || abs(deltaX) <= abs(deltaY)) return 0
    return if (deltaX < 0) 1 else -1
}
