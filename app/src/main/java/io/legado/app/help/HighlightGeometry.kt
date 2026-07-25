package io.legado.app.help

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin

object HighlightGeometry {
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
