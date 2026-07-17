package io.legado.app.ui.book.read.page

import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import io.legado.app.help.config.ReaderInfoPart
import io.legado.app.help.config.ReaderInfoTemplate
import io.legado.app.help.config.ReaderInfoValues
import kotlin.math.ceil
import kotlin.math.max

object BatteryIconGeometry {
    fun fillWidth(innerWidth: Int, level: Int): Int =
        innerWidth * level.coerceIn(0, 100) / 100
}

object ReaderInfoTemplateRenderer {
    fun render(template: String, values: ReaderInfoValues): CharSequence {
        val output = SpannableStringBuilder()
        ReaderInfoTemplate.parse(template, values).forEach { part ->
            when (part) {
                is ReaderInfoPart.Text -> output.append(part.value)
                is ReaderInfoPart.BatteryIcon -> {
                    val start = output.length
                    output.append('\uFFFC')
                    output.setSpan(
                        BatteryLevelSpan(part.level),
                        start,
                        output.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
        }
        return output
    }
}

class BatteryLevelSpan(private val level: Int) : ReplacementSpan() {

    override fun equals(other: Any?): Boolean =
        other is BatteryLevelSpan && level == other.level

    override fun hashCode(): Int = level

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        val dimensions = dimensions(paint.fontMetricsInt)
        return ceil(
            dimensions.horizontalGap * 2 + dimensions.bodyWidth + dimensions.terminalWidth
        ).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val fontMetrics = paint.fontMetricsInt
        val dimensions = dimensions(fontMetrics)
        val centerY = y + (fontMetrics.ascent + fontMetrics.descent) / 2f
        val bodyLeft = x + dimensions.horizontalGap
        val bodyTop = centerY - dimensions.bodyHeight / 2f
        val bodyRight = bodyLeft + dimensions.bodyWidth
        val bodyBottom = centerY + dimensions.bodyHeight / 2f
        val oldStyle = paint.style
        val oldStrokeWidth = paint.strokeWidth

        try {
            paint.strokeWidth = dimensions.strokeWidth
            paint.style = Paint.Style.STROKE
            canvas.drawRect(bodyLeft, bodyTop, bodyRight, bodyBottom, paint)

            paint.style = Paint.Style.FILL
            val terminalTop = centerY - dimensions.terminalHeight / 2f
            val terminalBottom = centerY + dimensions.terminalHeight / 2f
            canvas.drawRect(
                bodyRight,
                terminalTop,
                bodyRight + dimensions.terminalWidth,
                terminalBottom,
                paint,
            )

            val inset = minOf(dimensions.strokeWidth * 1.5f, dimensions.bodyHeight * 0.25f)
            val innerLeft = bodyLeft + inset
            val innerRight = bodyRight - inset
            val innerWidth = max(0f, innerRight - innerLeft).toInt()
            val fillWidth = BatteryIconGeometry.fillWidth(innerWidth, level)
            if (fillWidth > 0) {
                canvas.drawRect(
                    innerLeft,
                    bodyTop + inset,
                    innerLeft + fillWidth,
                    bodyBottom - inset,
                    paint,
                )
            }
        } finally {
            paint.style = oldStyle
            paint.strokeWidth = oldStrokeWidth
        }
    }

    private fun dimensions(fontMetrics: Paint.FontMetricsInt): Dimensions {
        val fontHeight = max(1, fontMetrics.descent - fontMetrics.ascent).toFloat()
        val bodyHeight = fontHeight * 0.58f
        return Dimensions(
            bodyWidth = fontHeight,
            bodyHeight = bodyHeight,
            terminalWidth = fontHeight * 0.12f,
            terminalHeight = bodyHeight * 0.42f,
            horizontalGap = fontHeight * 0.12f,
            strokeWidth = max(1f, fontHeight * 0.06f),
        )
    }

    private data class Dimensions(
        val bodyWidth: Float,
        val bodyHeight: Float,
        val terminalWidth: Float,
        val terminalHeight: Float,
        val horizontalGap: Float,
        val strokeWidth: Float,
    )
}
