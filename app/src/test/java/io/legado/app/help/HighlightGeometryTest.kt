package io.legado.app.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightGeometryTest {

    @Test
    fun `strike and box follow font metrics`() {
        val baseline = 30f
        val ascent = -18f
        val descent = 6f

        assertEquals(24f, HighlightGeometry.strikeY(baseline, ascent, descent), 1e-4f)
        assertEquals(12f, HighlightGeometry.glyphTop(baseline, ascent, 40f), 1e-4f)
        assertEquals(36f, HighlightGeometry.glyphBottom(baseline, descent, 40f), 1e-4f)
        assertEquals(0f, HighlightGeometry.glyphTop(10f, -18f, 40f), 1e-4f)
        assertEquals(40f, HighlightGeometry.glyphBottom(38f, 6f, 40f), 1e-4f)
    }

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

    @Test
    fun `highlight underline bottom follows kind width and distance`() {
        assertEquals(
            35f,
            HighlightGeometry.underlineRenderBottom(
                baseline = 30f,
                distancePx = 4f,
                strokeWidthPx = 2f,
                kind = HighlightStyle.Kind.SOLID,
            ),
            1e-4f,
        )
        assertEquals(
            37f,
            HighlightGeometry.underlineRenderBottom(
                baseline = 30f,
                distancePx = 4f,
                strokeWidthPx = 2f,
                kind = HighlightStyle.Kind.DOUBLE,
            ),
            1e-4f,
        )
        assertEquals(
            37f,
            HighlightGeometry.underlineRenderBottom(
                baseline = 30f,
                distancePx = 4f,
                strokeWidthPx = 2f,
                kind = HighlightStyle.Kind.WAVY,
            ),
            1e-4f,
        )
    }

    @Test
    fun `legacy rectangle keeps the full line height`() {
        val band = HighlightGeometry.fillBand(
            baseline = 30f,
            textSize = 20f,
            height = 40f,
            shape = HighlightStyle.FillShape.RECTANGLE,
            dp = 2f
        )

        assertEquals(0f, band.top, 1e-4f)
        assertEquals(40f, band.bottom, 1e-4f)
    }

    @Test
    fun `new fill shapes stay inside the line`() {
        for (shape in HighlightStyle.FillShape.entries - HighlightStyle.FillShape.RECTANGLE) {
            val band = HighlightGeometry.fillBand(30f, 20f, 40f, shape, 2f)

            assertTrue("shape=$shape", band.top in 0f..40f)
            assertTrue("shape=$shape", band.bottom in band.top..40f)
        }
    }

    @Test
    fun `pill band may clamp to both line edges`() {
        val band = HighlightGeometry.fillBand(
            baseline = 10f,
            textSize = 40f,
            height = 20f,
            shape = HighlightStyle.FillShape.PILL,
            dp = 2f
        )

        assertEquals(0f, band.top, 1e-4f)
        assertEquals(20f, band.bottom, 1e-4f)
    }
}
