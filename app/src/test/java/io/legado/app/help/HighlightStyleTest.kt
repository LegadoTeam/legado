package io.legado.app.help

import com.google.gson.Gson
import io.legado.app.help.HighlightStyle.Deco
import io.legado.app.help.HighlightStyle.FillShape
import io.legado.app.help.HighlightStyle.Kind
import io.legado.app.help.HighlightStyle.Underline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            HighlightStyle(fill = 3, bold = true, strike = Deco(4))
        )
        assertEquals(3, merged.fill)
        assertEquals(2, merged.textColor)
        assertTrue(merged.bold)
        assertEquals(Underline(Kind.SOLID), merged.underline)
        assertEquals(Deco(4), merged.strike)
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
