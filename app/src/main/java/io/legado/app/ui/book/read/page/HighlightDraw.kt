package io.legado.app.ui.book.read.page

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import io.legado.app.help.HighlightGeometry
import io.legado.app.help.HighlightStyle
import io.legado.app.help.PaintPool
import io.legado.app.ui.book.read.page.provider.ChapterProvider
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
        val wavePath: Path = Path(),
        val fillPath: Path = Path()
    )

    private val drawState = object : ThreadLocal<DrawState>() {
        override fun initialValue() = DrawState()
    }

    fun obtainTextPaint(base: Paint, style: HighlightStyle, color: Int, text: String): Paint {
        val paint = PaintPool.obtain()
        paint.set(base)
        paint.color = color
        paint.isFakeBoldText = paint.isFakeBoldText || style.bold
        if (style.italic) paint.textSkewX = -0.25f
        style.shadow?.let {
            paint.setShadowLayer(it.radius, it.dx, it.dy, it.color)
        }
        if (style.resolvedFontPath.isNotEmpty()) {
            ChapterProvider.getHighlightTypeface(style.resolvedFontPath)?.let {
                paint.typeface = Typeface.create(
                    it,
                    base.typeface?.style ?: Typeface.NORMAL
                )
                preserveTextAdvance(base, paint, text)
            }
        }
        return paint
    }

    private fun preserveTextAdvance(base: Paint, paint: Paint, text: String) {
        val targetWidth = base.measureText(text)
        val fontWidth = paint.measureText(text)
        paint.textScaleX *= textAdvanceScale(targetWidth, fontWidth)
    }

    internal fun textAdvanceScale(targetWidth: Float, fontWidth: Float): Float =
        if (targetWidth.isFinite() && fontWidth.isFinite() && targetWidth > 0f && fontWidth > 0f) {
            targetWidth / fontWidth
        } else {
            1f
        }

    fun recycleTextPaint(paint: Paint) {
        PaintPool.recycle(paint)
    }

    fun drawFillRun(
        canvas: Canvas,
        x0: Float,
        x1: Float,
        top: Float,
        bottom: Float,
        fill: Int,
        shape: HighlightStyle.FillShape
    ) {
        if (x1 <= x0 || bottom <= top) return
        val state = drawState.get()!!
        val fillPaint = state.fillPaint
        fillPaint.shader = null
        when (shape) {
            HighlightStyle.FillShape.RECTANGLE -> {
                fillPaint.color = fill
                canvas.drawRect(x0, top, x1, bottom, fillPaint)
            }

            HighlightStyle.FillShape.ROUNDED -> {
                val radius = 3f.dpToPx()
                fillPaint.color = fill
                canvas.drawRoundRect(x0, top, x1, bottom, radius, radius, fillPaint)
            }

            HighlightStyle.FillShape.MARKER -> {
                fillPaint.color = fill
                fillPaint.shader = LinearGradient(
                    x0,
                    0f,
                    x1,
                    0f,
                    intArrayOf(fill and 0x00FFFFFF, fill, fill, fill and 0x00FFFFFF),
                    floatArrayOf(0f, 0.04f, 0.96f, 1f),
                    Shader.TileMode.CLAMP
                )
                val unit = 2f.dpToPx()
                state.fillPath.reset()
                state.fillPath.addRoundRect(
                    x0,
                    top,
                    x1,
                    bottom,
                    floatArrayOf(
                        unit, unit * 2f,
                        unit * 2.5f, unit,
                        unit * 1.5f, unit * 2f,
                        unit * 2f, unit
                    ),
                    Path.Direction.CW
                )
                canvas.drawPath(state.fillPath, fillPaint)
                fillPaint.shader = null
            }

            HighlightStyle.FillShape.HALF,
            HighlightStyle.FillShape.BASELINE -> {
                fillPaint.color = fill
                canvas.drawRect(x0, top, x1, bottom, fillPaint)
            }

            HighlightStyle.FillShape.PILL -> {
                val radius = (bottom - top) / 2f
                fillPaint.color = scaleAlpha(fill, 0.35f)
                canvas.drawRoundRect(x0, top, x1, bottom, radius, radius, fillPaint)
                val strokePaint = state.strokePaint
                strokePaint.strokeWidth = 1f.dpToPx()
                strokePaint.pathEffect = null
                strokePaint.color = fill
                val inset = strokePaint.strokeWidth / 2f
                if (x1 - x0 > inset * 2f && bottom - top > inset * 2f) {
                    val strokeRadius = (radius - inset).coerceAtLeast(0f)
                    canvas.drawRoundRect(
                        x0 + inset,
                        top + inset,
                        x1 - inset,
                        bottom - inset,
                        strokeRadius,
                        strokeRadius,
                        strokePaint
                    )
                }
            }
        }
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
        ascent: Float,
        descent: Float,
        underline: HighlightStyle.Underline?,
        strike: HighlightStyle.Deco?,
        box: HighlightStyle.Deco?,
        fallbackColor: Int
    ) {
        val state = drawState.get()!!
        val strokePaint = state.strokePaint
        val defaultStrokeWidth = 1.5f.dpToPx()
        strokePaint.strokeWidth = defaultStrokeWidth
        strokePaint.pathEffect = null

        underline?.normalized()?.takeIf { it.width > 0f }?.let {
            strokePaint.color = if (it.color != 0) it.color else fallbackColor
            val width = it.width.dpToPx()
            strokePaint.strokeWidth = width
            val y = baseline + it.distance.dpToPx()
            when (it.kind) {
                HighlightStyle.Kind.SOLID -> canvas.drawLine(x0, y, x1, y, strokePaint)
                HighlightStyle.Kind.DOUBLE -> {
                    val separation = width + 1f.dpToPx()
                    canvas.drawLine(
                        x0, y - separation / 2f, x1, y - separation / 2f, strokePaint
                    )
                    canvas.drawLine(
                        x0, y + separation / 2f, x1, y + separation / 2f, strokePaint
                    )
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
                    val radius = width / 2f
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

        strokePaint.strokeWidth = defaultStrokeWidth
        strike?.let {
            strokePaint.color = if (it.color != 0) it.color else fallbackColor
            val y = HighlightGeometry.strikeY(
                baseline,
                ascent,
                descent
            )
            canvas.drawLine(x0, y, x1, y, strokePaint)
        }

        box?.let {
            strokePaint.color = if (it.color != 0) it.color else fallbackColor
            val inset = strokePaint.strokeWidth / 2f
            val left = x0 + inset
            val top = HighlightGeometry.glyphTop(
                baseline,
                ascent,
                height
            ) + inset
            val right = x1 - inset
            val bottom = HighlightGeometry.glyphBottom(
                baseline,
                descent,
                height
            ) - inset
            if (right > left && bottom > top) {
                canvas.drawRect(left, top, right, bottom, strokePaint)
            }
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
            y,
            paint.strokeWidth.coerceAtLeast(1f.dpToPx()),
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

    private fun scaleAlpha(color: Int, factor: Float): Int {
        val alpha = ((color ushr 24) * factor).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (color and 0x00FFFFFF)
    }
}
