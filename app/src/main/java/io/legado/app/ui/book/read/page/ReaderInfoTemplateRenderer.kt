package io.legado.app.ui.book.read.page

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.text.style.TtsSpan
import io.legado.app.help.config.ReaderInfoPart
import io.legado.app.help.config.ReaderInfoTemplate
import io.legado.app.help.config.ReaderInfoValues
import kotlin.math.ceil
import kotlin.math.max

object BatteryIconGeometry {
    fun fillWidth(innerWidth: Int, level: Int): Int =
        innerWidth * level.coerceIn(0, 100) / 100

    fun centerY(baseline: Int, glyphTop: Int, glyphBottom: Int): Float =
        baseline + (glyphTop + glyphBottom) / 2f
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
                        BatteryLevelSpan(part.level, part.showLevel),
                        start,
                        output.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    output.setSpan(TtsSpan.TextBuilder("${part.level}%").build(),
                        start, output.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        return output
    }
}

class BatteryLevelSpan(private val level: Int, private val showLevel: Boolean = false) : ReplacementSpan() {

    private val digitBounds = Rect()

    override fun equals(other: Any?): Boolean =
        other is BatteryLevelSpan && level == other.level && showLevel == other.showLevel

    override fun hashCode(): Int = 31 * level + showLevel.hashCode()

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        val dimensions = dimensions(paint)
        if (showLevel && fm != null) {
            paint.getFontMetricsInt(fm)
            paint.getTextBounds("0", 0, 1, digitBounds)
            val center = BatteryIconGeometry.centerY(0, digitBounds.top, digitBounds.bottom)
            val extent = ceil(dimensions.bodyHeight / 2f + dimensions.strokeWidth / 2f).toInt()
            fm.ascent = minOf(fm.ascent, kotlin.math.floor(center).toInt() - extent)
            fm.descent = maxOf(fm.descent, ceil(center).toInt() + extent)
            fm.top = minOf(fm.top, fm.ascent)
            fm.bottom = maxOf(fm.bottom, fm.descent)
        }
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
        val dimensions = dimensions(paint)
        paint.getTextBounds("0", 0, 1, digitBounds)
        val centerY = BatteryIconGeometry.centerY(y, digitBounds.top, digitBounds.bottom)
        val bodyLeft = x + dimensions.horizontalGap
        val bodyTop = centerY - dimensions.bodyHeight / 2f
        val bodyRight = bodyLeft + dimensions.bodyWidth
        val bodyBottom = centerY + dimensions.bodyHeight / 2f
        val oldStyle = paint.style
        val oldStrokeWidth = paint.strokeWidth
        val oldAlign = paint.textAlign

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

            if (showLevel) {
                val label = level.coerceIn(0, 100).toString()
                paint.textAlign = Paint.Align.LEFT
                canvas.drawText(label, bodyLeft + (dimensions.bodyWidth - paint.measureText(label)) / 2f,
                    y.toFloat(), paint)
                return
            }

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
            paint.textAlign = oldAlign
        }
    }

    private fun dimensions(paint: Paint): Dimensions {
        val iconSize = max(1f, paint.textSize)
        val stroke = max(1f, iconSize * 0.06f)
        if (showLevel) paint.getTextBounds("0123456789", 0, 10, digitBounds)
        val bodyHeight = if (showLevel) max(iconSize * 0.58f, digitBounds.height() + stroke * 4)
            else iconSize * 0.58f
        return Dimensions(
            // Keep the slot stable when the battery changes between one, two and three digits.
            bodyWidth = if (showLevel) max(iconSize, paint.measureText("100") + stroke * 4) else iconSize,
            bodyHeight = bodyHeight,
            terminalWidth = iconSize * 0.12f,
            terminalHeight = bodyHeight * 0.42f,
            horizontalGap = iconSize * 0.12f,
            strokeWidth = stroke,
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
