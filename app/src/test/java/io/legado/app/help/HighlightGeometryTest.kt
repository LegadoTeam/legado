package io.legado.app.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightGeometryTest {

    @Test
    fun `wave starts on baseline and reaches the endpoint`() {
        val points = HighlightGeometry.wavePoints(0f, 11f, 100f, 3f, 8f, 2f)
        assertEquals(0f, points[0], 1e-4f)
        assertEquals(100f, points[1], 1e-4f)
        assertEquals(11f, points[points.size - 2], 1e-4f)
    }

    @Test
    fun `wave stays within its amplitude`() {
        val points = HighlightGeometry.wavePoints(0f, 40f, 50f, 3f, 8f, 1f)
        var index = 1
        while (index < points.size) {
            assertTrue(points[index] in 46.999f..53.001f)
            index += 2
        }
    }

    @Test
    fun `invalid wave range is empty`() {
        assertEquals(0, HighlightGeometry.wavePoints(5f, 5f, 0f, 1f, 1f, 1f).size)
    }
}
