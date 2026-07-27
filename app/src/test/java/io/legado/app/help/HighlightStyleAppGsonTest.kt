package io.legado.app.help

import io.legado.app.help.HighlightStyle.Deco
import io.legado.app.help.HighlightStyle.FillShape
import io.legado.app.help.HighlightStyle.Kind
import io.legado.app.help.HighlightStyle.Shadow
import io.legado.app.help.HighlightStyle.Underline
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HighlightStyleAppGsonTest {

    @Test
    fun `app Gson preserves every underline kind`() {
        for (kind in Kind.entries) {
            val style = HighlightStyle(underline = Underline(kind, 0xFF00FF00.toInt()))
            val json = GSON.toJson(style)
            val restored = GSON.fromJsonObject<HighlightStyle>(json).getOrThrow()
            assertEquals("kind=$kind json=$json", kind, restored.underline?.kind)
        }
    }

    @Test
    fun `app Gson preserves the full style`() {
        val style = HighlightStyle(
            fill = 0x80FFFF00.toInt(),
            fillShape = FillShape.MARKER,
            textColor = 0xFFFF0000.toInt(),
            bold = true,
            underline = Underline(Kind.DASHED, 0xFF00FF00.toInt()),
            strike = Deco(0xFF0000FF.toInt()),
            shadow = Shadow(4f, 0f, 0f, 0x80FFFFFF.toInt())
        )
        val restored = GSON.fromJsonObject<HighlightStyle>(GSON.toJson(style)).getOrThrow()
        assertEquals(style, restored)
    }

    @Test
    fun `custom font field is preserved with supported channels`() {
        val restored = GSON.fromJsonObject<HighlightStyle>(
            """{"fill":-2130771969,"bold":true,"fontPath":"legacy.ttf"}"""
        ).getOrThrow()

        assertEquals(-2130771969, restored.fill)
        assertEquals(true, restored.bold)
        assertEquals(FillShape.RECTANGLE, restored.resolvedFillShape)
        assertNull(restored.shadow)
        assertEquals("legacy.ttf", restored.fontPath)
        assertEquals(restored, HighlightStyle.merge(restored, HighlightStyle()))
    }

    @Test
    fun `missing or null font fields keep legacy styles readable`() {
        val missing = GSON.fromJsonObject<HighlightStyle>("""{"fill":1}""").getOrThrow()
        val nullFont = GSON.fromJsonObject<HighlightStyle>(
            """{"fill":1,"fontPath":null}"""
        ).getOrThrow()

        assertEquals("", missing.resolvedFontPath)
        assertEquals("", nullFont.resolvedFontPath)
    }
}
