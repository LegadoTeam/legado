package io.legado.app.ui.book.read.page

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import io.legado.app.help.HighlightGeometry
import io.legado.app.help.HighlightStyle
import io.legado.app.help.PaintPool
import io.legado.app.utils.dpToPx

object HighlightDraw {

    private data class DrawState(
        val strokePaint: Paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
        },
        val fillPaint: Paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        },
        val wavePath: Path = Path()
    )

    private val drawState = object : ThreadLocal<DrawState>() {
        override fun initialValue() = DrawState()
    }

    fun obtainTextPaint(base: Paint, style: HighlightStyle, color: Int): Paint {
        val paint = PaintPool.obtain()
        paint.set(base)
        paint.color = color
        paint.isFakeBoldText = paint.isFakeBoldText || style.bold
        if (style.italic) paint.textSkewX = -0.25f
        return paint
    }

    fun recycleTextPaint(paint: Paint) {
        PaintPool.recycle(paint)
    }

    fun drawEmphasis(canvas: Canvas, start: Float, end: Float, height: Float, color: Int) {
        val fillPaint = drawState.get()!!.fillPaint
        val radius = 1.6f.dpToPx()
        fillPaint.color = color
        canvas.drawCircle(
            (start + end) / 2f,
            height - radius - 0.5f.dpToPx(),
            radius,
            fillPaint
        )
    }

    fun drawRun(
        canvas: Canvas,
        x0: Float,
        x1: Float,
        baseline: Float,
        height: Float,
        underline: HighlightStyle.Underline?,
        strike: HighlightStyle.Deco?,
        box: HighlightStyle.Deco?,
        fallbackColor: Int
    ) {
        val state = drawState.get()!!
        val strokePaint = state.strokePaint
        strokePaint.strokeWidth = 1.5f.dpToPx()
        strokePaint.pathEffect = null

        underline?.let {
            strokePaint.color = if (it.color != 0) it.color else fallbackColor
            val y = height - 2f.dpToPx()
            when (it.kind) {
                HighlightStyle.Kind.SOLID -> canvas.drawLine(x0, y, x1, y, strokePaint)
                HighlightStyle.Kind.DOUBLE -> {
                    strokePaint.strokeWidth = 1f.dpToPx()
                    canvas.drawLine(
                        x0, height - 3.5f.dpToPx(), x1, height - 3.5f.dpToPx(), strokePaint
                    )
                    canvas.drawLine(
                        x0, height - 1.5f.dpToPx(), x1, height - 1.5f.dpToPx(), strokePaint
                    )
                    strokePaint.strokeWidth = 1.5f.dpToPx()
                }

                HighlightStyle.Kind.DASHED -> {
                    val dashLength = 6f.dpToPx()
                    val step = dashLength + 4f.dpToPx()
                    var start = x0
                    while (start < x1) {
                        canvas.drawLine(start, y, minOf(start + dashLength, x1), y, strokePaint)
                        start += step
                    }
                }
                HighlightStyle.Kind.DOTTED -> {
                    val fillPaint = state.fillPaint
                    val radius = 0.9f.dpToPx()
                    val step = 3.5f.dpToPx()
                    fillPaint.color = strokePaint.color
                    var center = x0 + radius
                    while (center <= x1) {
                        canvas.drawCircle(center, y, radius, fillPaint)
                        center += step
                    }
                }
                HighlightStyle.Kind.WAVY -> drawWave(
                    canvas, x0, x1, y, strokePaint, state.wavePath
                )
            }
        }

        strike?.let {
            strokePaint.color = if (it.color != 0) it.color else fallbackColor
            val y = baseline * 0.7f
            canvas.drawLine(x0, y, x1, y, strokePaint)
        }

        box?.let {
            strokePaint.color = if (it.color != 0) it.color else fallbackColor
            val inset = 0.5f.dpToPx()
            canvas.drawRect(x0 + inset, inset, x1 - inset, height - inset, strokePaint)
        }
    }

    private fun drawWave(
        canvas: Canvas,
        x0: Float,
        x1: Float,
        y: Float,
        paint: Paint,
        path: Path
    ) {
        val points = HighlightGeometry.wavePoints(
            x0,
            x1,
            y - 1f.dpToPx(),
            1.5f.dpToPx(),
            6f.dpToPx(),
            2f.dpToPx()
        )
        if (points.size < 4) return
        path.reset()
        path.moveTo(points[0], points[1])
        var index = 2
        while (index < points.size) {
            path.lineTo(points[index], points[index + 1])
            index += 2
        }
        canvas.drawPath(path, paint)
    }
}
