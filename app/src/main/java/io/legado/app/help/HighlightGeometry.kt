package io.legado.app.help

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.math.sqrt

object HighlightGeometry {

    const val GLYPH_BOX_CENTER_RATIO = 0.37f

    data class Band(val top: Float, val bottom: Float)

    /** Minimum ink-to-edge distance that retains the requested horizontal cap radius. */
    fun pillClearance(radius: Float, inkTop: Float, inkBottom: Float,
        top: Float, bottom: Float, border: Float): Float {
        val radiusY = (bottom - top) / 2f - border
        if (radiusY <= 0f) return maxOf(radius, border + 0.01f)
        val centerY = (top + bottom) / 2f
        val vertical = maxOf(kotlin.math.abs(inkTop - centerY), kotlin.math.abs(inkBottom - centerY))
            .div(radiusY).coerceIn(0f, 1f)
        return border + (radius - border).coerceAtLeast(0f) * (1f - sqrt(1f - vertical * vertical)) + 0.01f
    }

    /** Largest horizontal end radius whose inner ellipse still contains this glyph's ink box. */
    fun pillRadiusX(
        desired: Float,
        clearance: Float,
        inkTop: Float,
        inkBottom: Float,
        top: Float,
        bottom: Float,
        border: Float
    ): Float {
        // An icon touching the ink leaves no room for both a border and a curved cap.
        if (clearance <= border) return 0f
        val radiusY = (bottom - top) / 2f - border
        if (radiusY <= 0f) return 0f
        val centerY = (top + bottom) / 2f
        val vertical = maxOf(kotlin.math.abs(inkTop - centerY), kotlin.math.abs(inkBottom - centerY))
            .div(radiusY).coerceIn(0f, 1f)
        val intrusion = 1f - sqrt(1f - vertical * vertical)
        if (intrusion == 0f) return desired
        return minOf(desired, border + (clearance - border) / intrusion).coerceAtLeast(0f)
    }

    fun fillBand(
        baseline: Float,
        textSize: Float,
        height: Float,
        shape: HighlightStyle.FillShape,
        dp: Float
    ): Band {
        val (top, bottom) = when (shape) {
            HighlightStyle.FillShape.RECTANGLE -> 0f to height
            HighlightStyle.FillShape.HALF -> {
                baseline - textSize * 0.5f to baseline + 2f * dp
            }

            HighlightStyle.FillShape.BASELINE -> {
                baseline + dp to baseline + 5f * dp
            }

            else -> {
                baseline - textSize * 0.9f - 2f * dp to
                    baseline + textSize * 0.16f + 2f * dp
            }
        }
        val clampedTop = top.coerceIn(0f, height)
        return Band(clampedTop, bottom.coerceIn(clampedTop, height))
    }

    fun strikeY(baseline: Float, ascent: Float, descent: Float): Float {
        return baseline + (ascent + descent) / 2f
    }

    fun underlineRenderBottom(
        baseline: Float,
        distancePx: Float,
        strokeWidthPx: Float,
        kind: HighlightStyle.Kind
    ): Float {
        val width = strokeWidthPx.coerceAtLeast(0f)
        val distance = distancePx.coerceAtLeast(0f)
        val extent = when (kind) {
            HighlightStyle.Kind.DOUBLE -> width + 1f
            HighlightStyle.Kind.WAVY -> width * 1.5f
            else -> width / 2f
        }
        return baseline + distance + extent
    }

    fun glyphTop(baseline: Float, ascent: Float, height: Float): Float {
        return (baseline + ascent).coerceIn(0f, height)
    }

    fun glyphBottom(baseline: Float, descent: Float, height: Float): Float {
        return (baseline + descent).coerceIn(0f, height)
    }

    fun wavePoints(
        x0: Float,
        x1: Float,
        baseY: Float,
        amplitude: Float,
        wavelength: Float,
        step: Float
    ): FloatArray {
        if (x1 <= x0 || step <= 0f || wavelength <= 0f) return FloatArray(0)
        val segments = ceil((x1 - x0) / step).toInt()
        val points = FloatArray((segments + 1) * 2)
        for (index in 0..segments) {
            val x = if (index == segments) x1 else x0 + index * step
            val phase = (x - x0) / wavelength * (2.0 * PI)
            points[index * 2] = x
            points[index * 2 + 1] = (baseY + amplitude * sin(phase)).toFloat()
        }
        return points
    }

}
