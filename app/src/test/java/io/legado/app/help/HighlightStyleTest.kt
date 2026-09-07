package io.legado.app.help

import com.google.gson.Gson
import io.legado.app.help.HighlightStyle.Deco
import io.legado.app.help.HighlightStyle.FillShape
import io.legado.app.help.HighlightStyle.Kind
import io.legado.app.help.HighlightStyle.Shadow
import io.legado.app.help.HighlightStyle.Underline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightStyleTest {

    @Test
    fun `empty style needs no per-column drawing`() {
        val style = HighlightStyle()
        assertTrue(style.isEmpty)
        assertFalse(style.needsPerColumnDraw)
    }

    @Test
    fun `fill-only style keeps the fast drawing path`() {
        val style = HighlightStyle(fill = 0x80FFFF00.toInt())
        assertFalse(style.isEmpty)
        assertFalse(style.needsPerColumnDraw)
        assertEquals(FillShape.RECTANGLE, style.resolvedFillShape)
    }

    @Test
    fun `text decorations need per-column drawing`() {
        assertTrue(HighlightStyle(textColor = 1).needsPerColumnDraw)
        assertTrue(HighlightStyle(bold = true).needsPerColumnDraw)
        assertTrue(HighlightStyle(italic = true).needsPerColumnDraw)
        assertTrue(HighlightStyle(underline = Underline(Kind.WAVY)).needsPerColumnDraw)
        assertTrue(HighlightStyle(strike = Deco()).needsPerColumnDraw)
        assertTrue(HighlightStyle(box = Deco()).needsPerColumnDraw)
        assertTrue(HighlightStyle(emphasis = Deco()).needsPerColumnDraw)
        assertTrue(HighlightStyle(shadow = Shadow()).needsPerColumnDraw)
        assertTrue(HighlightStyle(fontPath = "font.ttf").needsPerColumnDraw)
    }

    @Test
    fun `merge is last-wins per configured channel`() {
        val base = HighlightStyle(
            fill = 1,
            textColor = 2,
            underline = Underline(Kind.SOLID)
        )
        val merged = HighlightStyle.merge(
            base,
            HighlightStyle(fill = 3, bold = true, strike = Deco(4), fontPath = "font.ttf")
        )
        assertEquals(3, merged.fill)
        assertEquals(2, merged.textColor)
        assertTrue(merged.bold)
        assertEquals(Underline(Kind.SOLID), merged.underline)
        assertEquals(Deco(4), merged.strike)
        assertEquals("font.ttf", merged.fontPath)
    }

    @Test
    fun `shadow merge is last-wins and null keeps the base`() {
        val base = HighlightStyle(shadow = Shadow(radius = 5f, color = 1))
        val replacement = Shadow(radius = 2f, dx = 0f, dy = 0f, color = 2)

        assertEquals(replacement, HighlightStyle.merge(base, HighlightStyle(shadow = replacement)).shadow)
        assertEquals(base, HighlightStyle.merge(base, HighlightStyle()))
    }

    @Test
    fun `shadow values are normalized before drawing`() {
        assertEquals(
            Shadow(radius = 0f, dx = 0f, dy = 0f, color = 7),
            Shadow(-1f, Float.POSITIVE_INFINITY, Float.NaN, 7).normalized()
        )
        assertEquals(
            Shadow(radius = 10f, dx = -10f, dy = 10f),
            Shadow(radius = 99f, dx = -99f, dy = 99f).normalized()
        )
    }

    @Test
    fun `underline values are normalized before drawing`() {
        val normalized = Underline(
            kind = Kind.DASHED,
            width = Float.POSITIVE_INFINITY,
            distance = -4f,
        ).normalized()

        assertEquals(Underline.DEFAULT_WIDTH, normalized.width, 0f)
        assertEquals(Underline.MIN_DISTANCE, normalized.distance, 0f)
        assertEquals(Kind.DASHED, normalized.kind)
        assertEquals(
            Underline.MAX_WIDTH,
            Underline(width = Underline.MAX_WIDTH + 1f).normalized().width,
            0f,
        )
        assertEquals(Underline(), Underline(width = 1f, distance = 0f).normalized())
    }

    @Test
    fun `normalization reuses valid style instances`() {
        val shadow = Shadow(radius = 4f, dx = -2f, dy = 6f)
        val style = HighlightStyle(shadow = shadow)

        assertSame(shadow, shadow.normalized())
        assertSame(style, style.normalized())
    }

    @Test
    fun `empty style does not overwrite configured channels`() {
        val base = HighlightStyle(fill = 9, textColor = 8)
        assertEquals(base, HighlightStyle.merge(base, HighlightStyle()))
    }

    @Test
    fun `style survives Gson round trip`() {
        val gson = Gson()
        val style = HighlightStyle(
            fill = 0x80FFFF00.toInt(),
            textColor = 0xFFFF0000.toInt(),
            bold = true,
            fontPath = "content://fonts/reader.ttf",
            underline = Underline(Kind.DASHED, 0xFF00FF00.toInt()),
            strike = Deco(0xFF0000FF.toInt())
        )
        assertEquals(style, gson.fromJson(gson.toJson(style), HighlightStyle::class.java))
    }

    @Test
    fun `fill shape follows the winning fill channel`() {
        val base = HighlightStyle(fill = 1, fillShape = FillShape.MARKER)
        val shaped = HighlightStyle.merge(
            base,
            HighlightStyle(fill = 2, fillShape = FillShape.PILL)
        )
        val legacy = HighlightStyle.merge(base, HighlightStyle(fill = 3))

        assertEquals(FillShape.PILL, shaped.resolvedFillShape)
        assertEquals(FillShape.RECTANGLE, legacy.resolvedFillShape)
    }

    @Test
    fun `shape alone does not enable highlighting`() {
        val style = HighlightStyle(fillShape = FillShape.HALF)

        assertTrue(style.isEmpty)
        assertFalse(style.needsPerColumnDraw)
    }
}
