package io.legado.app.ui.book.read.page

import android.graphics.Canvas
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import io.legado.app.help.book.isPdf
import io.legado.app.model.ReadBook
import kotlin.math.abs

/** One camera for the reader's current/previous/next page, including a two-page spread. */
internal class PdfZoom(private val view: ReadView) {
    var scale = 1f
        private set
    var offsetX = 0f
        private set
    var offsetY = 0f
        private set
    private var bookUrl: String? = null
    private var bounds = RectF()
    private var owned = false
    val isInteracting: Boolean get() = owned
    private var multiplePointers = false
    private var moved = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private val detector = ScaleGestureDetector(view.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val next = (scale * detector.scaleFactor).coerceIn(1f, 5f)
                val ratio = next / scale
                val focusY = detector.focusY - view.curPage.contentViewTop
                offsetX = detector.focusX - (detector.focusX - offsetX) * ratio
                offsetY = focusY - (focusY - offsetY) * ratio
                scale = next
                clamp()
                redraw()
                return true
            }
        }).apply { isQuickScaleEnabled = false }

    fun isEnabled(): Boolean {
        val book = ReadBook.book
        if (book?.bookUrl != bookUrl) {
            bookUrl = book?.bookUrl
            scale = 1f
            offsetX = 0f
            offsetY = 0f
            owned = false
        }
        return book?.isPdf == true
    }

    fun setBounds(rect: RectF) {
        if (bounds == rect) return
        if (!bounds.isEmpty && !rect.isEmpty) {
            offsetX = rect.left + (offsetX - bounds.left) * rect.width() / bounds.width()
            offsetY = rect.top + (offsetY - bounds.top) * rect.height() / bounds.height()
        }
        bounds.set(rect)
        clamp()
    }

    private fun clamp() {
        offsetX = offsetX.coerceIn(bounds.right * (1f - scale), bounds.left * (1f - scale))
        offsetY = offsetY.coerceIn(bounds.bottom * (1f - scale), bounds.top * (1f - scale))
    }

    fun transform(canvas: Canvas) {
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
    }

    fun save(): Bundle = Bundle().apply {
        putString("book", bookUrl)
        putFloat("scale", scale)
        putFloat("x", offsetX)
        putFloat("y", offsetY)
        putFloatArray("bounds", floatArrayOf(bounds.left, bounds.top, bounds.right, bounds.bottom))
    }

    fun restore(state: Bundle?) {
        if (state == null) return
        bookUrl = state.getString("book")
        scale = state.getFloat("scale", 1f).coerceIn(1f, 5f)
        offsetX = state.getFloat("x")
        offsetY = state.getFloat("y")
        state.getFloatArray("bounds")?.takeIf { it.size == 4 }?.let {
            bounds.set(it[0], it[1], it[2], it[3])
        }
    }

    private fun redraw() {
        view.curPage.invalidateContentView()
        view.prevPage.invalidateContentView()
        view.nextPage.invalidateContentView()
        view.invalidate()
    }

    /** At 1x, keep ordinary swipe navigation. Once zoomed, a drag pans but a tap still acts. */
    fun onTouch(event: MotionEvent, tap: (Float, Float) -> Unit): Boolean {
        if (!isEnabled()) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            owned = scale > 1f
            multiplePointers = false
            moved = false
            downX = event.x
            downY = event.y
            lastX = event.x
            lastY = event.y
            if (owned) {
                view.cancelTouchGestures()
                view.pageDelegate?.apply { isCancel = true; abortAnim() }
                view.autoPager.pause()
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            owned = true
            multiplePointers = true
            view.cancelTouchGestures()
            view.pageDelegate?.apply { isCancel = true; abortAnim() }
            view.autoPager.pause()
        }
        detector.onTouchEvent(event)
        if (!owned) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                moved = moved || abs(event.x - downX) > slop || abs(event.y - downY) > slop
                if (!multiplePointers && moved) {
                    offsetX += event.x - lastX
                    offsetY += event.y - lastY
                    clamp()
                    redraw()
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                owned = false
                if (!multiplePointers && !moved) {
                    view.pageDelegate?.onDown()
                    tap(downX, downY)
                }
                view.callBack.screenOffTimerStart()
                view.autoPager.resume()
                redraw()
            }
            MotionEvent.ACTION_CANCEL -> { owned = false; view.autoPager.resume(); redraw() }
        }
        return true
    }
}
