package io.legado.app.ui.book.read.page.delegate

import android.graphics.Canvas
import android.view.MotionEvent
import io.legado.app.ui.book.read.page.NativePageTurnGesture
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.PageDirection

/** Instant pagination for firmware effects, without canvas snapshots or intermediate frames. */
class IReaderPageDelegate(readView: ReadView) : PageDelegate(readView) {

    private val swipe = NativePageTurnGesture()

    override fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipe.begin()
                abortAnim()
            }

            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // A pinch or two-finger action must not become a one-finger page turn.
                swipe.cancel()
                isCancel = true
                // ReadView routes an unmoved UP to its tap handler. Keep this gesture
                // consumed until UP/CANCEL instead of asynchronously resetting isMoved.
                isMoved = swipe.consumed
                isRunning = false
                isStarted = false
            }

            MotionEvent.ACTION_MOVE -> {
                val direction = swipeDirection(event)
                if (direction != PageDirection.NONE) {
                    if (!isMoved) readView.markReadPositionChanged()
                    isMoved = true
                    isRunning = true
                    setDirection(direction)
                }
                // Keep gesture coordinates without drawing intermediate LCD-style frames.
                readView.setTouchPoint(event.x, event.y, false)
            }

            MotionEvent.ACTION_UP -> {
                val direction = swipeDirection(event)
                if (direction == PageDirection.NONE) {
                    isCancel = true
                    finishGesture()
                } else {
                    isCancel = false
                    setDirection(direction)
                    onAnimStart(readView.defaultAnimationSpeed)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                isCancel = true
                swipe.cancel()
                finishGesture()
            }
        }
    }

    private fun swipeDirection(event: MotionEvent) = swipe.direction(
        startX, startY, event.x, event.y, readView.pageSlopSquare2,
    )

    override fun onAnimStart(animationSpeed: Int) {
        // A previous canceled touch must not suppress a subsequent tap or hardware key.
        if (!isMoved) isCancel = false
        val canTurn = when (mDirection) {
            PageDirection.NEXT -> !isCancel && hasNext()
            PageDirection.PREV -> !isCancel && hasPrev()
            else -> false
        }
        if (canTurn) {
            readView.fillPage(mDirection)
        }
        finishGesture()
    }

    private fun finishGesture() {
        // Instant turns have no scroller to finish. A posted reset could otherwise
        // clear the flags of a new DOWN/MOVE arriving before that runnable executes.
        isStarted = false
        isRunning = false
        setDirection(PageDirection.NONE)
        swipe.finish(readView.isTouching)
        isMoved = swipe.consumed
        readView.invalidate()
    }

    override fun abortAnim() {
        isStarted = false
        isMoved = false
        isRunning = false
        readView.isAbortAnim = false
    }

    override fun nextPageByAnim(animationSpeed: Int) {
        abortAnim()
        setDirection(PageDirection.NEXT)
        onAnimStart(animationSpeed)
    }

    override fun prevPageByAnim(animationSpeed: Int) {
        abortAnim()
        setDirection(PageDirection.PREV)
        onAnimStart(animationSpeed)
    }

    override fun computeScroll() = Unit
    override fun onDraw(canvas: Canvas) = Unit
    override fun onAnimStop() = Unit
}
