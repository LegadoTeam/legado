package io.legado.app.ui.book.read.page.entities.column

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.annotation.Keep
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.emptyTextLine
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.ReviewColumnGeometry
import kotlin.math.roundToInt

/**
 * 评论按钮列
 */
@Keep
data class ReviewColumn(
    override var start: Float,
    override var end: Float,
    var count: Int = 0
) : BaseColumn {

    override var textLine: TextLine = emptyTextLine

    override fun isTouch(x: Float): Boolean {
        if (count == 0) return false
        val extraTouchWidth = if (textLine.isImage) {
            0f
        } else {
            ((end - start) * 0.35f).coerceAtLeast(textLine.height * 0.15f)
        }
        return x > start - extraTouchWidth && x < end + extraTouchWidth
    }

    fun isTouch(x: Float, y: Float, relativeOffset: Float): Boolean {
        if (!isTouch(x)) return false
        if (!textLine.isImage) return true
        val height = minOf(ChapterProvider.getReviewHeight(false), textLine.height) * 0.9f
        val baseLine = textLine.lineBase - textLine.lineTop
        val localY = y - textLine.lineTop - relativeOffset
        return height > 0f && localY in (baseLine - height)..baseLine
    }

    override fun draw(view: ContentTextView, canvas: Canvas) {
        val configuredHeight = ChapterProvider.getReviewHeight(textLine.isTitle)
        val height = if (textLine.isImage) {
            minOf(configuredHeight, textLine.height)
        } else {
            configuredHeight
        }
        if (height > 0f) {
            drawToCanvas(
                canvas,
                textLine.lineBase - textLine.lineTop,
                height,
                containerHeight = if (textLine.isImage) null else textLine.height,
            )
        }
    }

    val countText: String
        get() = ChapterProvider.getReviewCountText(count)

    val path by lazy { Path() }
    private val iconRect by lazy { RectF() }

    fun drawToCanvas(
        canvas: Canvas,
        baseLine: Float,
        height: Float,
        containerHeight: Float? = null,
    ) {
        if (count == 0) return
        val iconHeight = height * 0.9f
        ChapterProvider.getReviewIconBitmap(
            count,
            (end - start).roundToInt().coerceAtLeast(1),
            iconHeight.roundToInt().coerceAtLeast(1),
        )?.let { bitmap ->
            val drawHeight = minOf(iconHeight, (end - start) * bitmap.height / bitmap.width)
            val iconTop = containerHeight?.let {
                ReviewColumnGeometry.centeredTop(it, drawHeight)
            } ?: baseLine - drawHeight
            iconRect.set(start, iconTop, end, iconTop + drawHeight)
            canvas.drawBitmap(bitmap, null, iconRect, null)
            return
        }
        path.reset()
        path.moveTo(start + 1, baseLine - height * 2 / 5)
        path.lineTo(start + height / 6, baseLine - height * 0.55f)
        path.lineTo(start + height / 6, baseLine - height * 0.8f)
        path.lineTo(end - 1, baseLine - height * 0.8f)
        path.lineTo(end - 1, baseLine)
        path.lineTo(start + height / 6, baseLine)
        path.lineTo(start + height / 6, baseLine - height / 4)
        path.close()
        val reviewPaint = ChapterProvider.reviewPaint
        reviewPaint.style = Paint.Style.STROKE
        canvas.drawPath(path, reviewPaint)
        reviewPaint.style = Paint.Style.FILL
        canvas.drawText(
            countText,
            (start + height / 9 + end) / 2,
            baseLine - height * 0.23f,
            reviewPaint
        )
    }


}
