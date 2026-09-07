package io.legado.app.help

import io.legado.app.help.HighlightMatcher.LineSpec
import io.legado.app.help.HighlightMatcher.Range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightMatcherTest {

    @Test
    fun `half-open range colors only intersecting columns`() {
        val lines = listOf(LineSpec(3, listOf(1, 1, 1), false))
        val result = HighlightMatcher.resolve(
            0,
            lines,
            listOf(Range(0, 2, HighlightStyle(fill = 0x11)))
        )
        assertEquals(listOf(0x11, 0x11, 0), result[0].map { it?.fill ?: 0 })
    }

    @Test
    fun `paragraph end advances the next line by one`() {
        val lines = listOf(
            LineSpec(6, List(6) { 1 }, true),
            LineSpec(3, List(3) { 1 }, false)
        )
        val result = HighlightMatcher.resolve(
            10,
            lines,
            listOf(Range(18, 19, HighlightStyle(fill = 0x77)))
        )
        assertEquals(listOf(0, 0x77, 0), result[1].map { it?.fill ?: 0 })
    }

    @Test
    fun `overlapping ranges merge per channel`() {
        val result = HighlightMatcher.resolve(
            0,
            listOf(LineSpec(3, listOf(1, 1, 1), false)),
            listOf(
                Range(0, 3, HighlightStyle(fill = 11)),
                Range(1, 2, HighlightStyle(textColor = 22, bold = true))
            )
        )
        assertEquals(11, result[0][1]?.fill)
        assertEquals(22, result[0][1]?.textColor)
        assertTrue(result[0][1]?.bold == true)
    }

    @Test
    fun `zero-length non-text column is not colored`() {
        val result = HighlightMatcher.resolve(
            0,
            listOf(LineSpec(2, listOf(1, 0, 1), false)),
            listOf(Range(0, 2, HighlightStyle(fill = 0x33)))
        )
        assertEquals(listOf(0x33, 0, 0x33), result[0].map { it?.fill ?: 0 })
    }

    @Test
    fun `multi-character column uses its full half-open interval`() {
        val result = HighlightMatcher.resolve(
            0,
            listOf(LineSpec(3, listOf(2, 1), false)),
            listOf(Range(1, 2, HighlightStyle(fill = 0x22)))
        )
        assertEquals(listOf(0x22, 0), result[0].map { it?.fill ?: 0 })
    }

    @Test
    fun `title line accepts only ranges opted into titles`() {
        val result = HighlightMatcher.resolve(
            0,
            listOf(LineSpec(6, listOf(3, 3), true, isTitle = true)),
            listOf(
                Range(0, 3, HighlightStyle(fill = 0x11), applyToTitle = true),
                Range(3, 6, HighlightStyle(fill = 0x22))
            )
        )
        assertNotNull(result[0][0])
        assertNull(result[0][1])
    }

    @Test
    fun `non-title line ignores title opt-in flag`() {
        val result = HighlightMatcher.resolve(
            0,
            listOf(LineSpec(6, listOf(3, 3), true)),
            listOf(Range(0, 6, HighlightStyle(fill = 0x22)))
        )
        assertNotNull(result[0][0])
        assertNotNull(result[0][1])
    }

    @Test
    fun `body line accepts only ranges opted into body`() {
        val result = HighlightMatcher.resolve(
            0,
            listOf(LineSpec(6, listOf(3, 3), true)),
            listOf(
                Range(
                    0,
                    3,
                    HighlightStyle(fill = 0x11),
                    applyToTitle = true,
                    applyToBody = false
                ),
                Range(3, 6, HighlightStyle(fill = 0x22))
            )
        )

        assertNull(result[0][0])
        assertNotNull(result[0][1])
    }

    @Test
    fun `title and body share one chapter coordinate space`() {
        val result = HighlightMatcher.resolve(
            pageBase = 10,
            lines = listOf(
                LineSpec(3, listOf(1, 1, 1), false, isTitle = true),
                LineSpec(3, listOf(1, 1, 1), false)
            ),
            ranges = listOf(
                Range(10, 13, HighlightStyle(fill = 0x11), applyToTitle = true),
                Range(13, 15, HighlightStyle(fill = 0x22))
            )
        )

        assertEquals(listOf(0x11, 0x11, 0x11), result[0].map { it?.fill ?: 0 })
        assertEquals(listOf(0x22, 0x22, 0), result[1].map { it?.fill ?: 0 })
    }
}
