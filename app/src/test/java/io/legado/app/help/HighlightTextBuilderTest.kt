package io.legado.app.help

import io.legado.app.help.HighlightTextBuilder.LineInput
import org.junit.Assert.assertEquals
import org.junit.Test

class HighlightTextBuilderTest {

    @Test
    fun `columns concatenate and paragraph ends consume one position`() {
        val text = HighlightTextBuilder.build(
            listOf(
                LineInput("abcd", true),
                LineInput("e", false)
            )
        )

        assertEquals("abcd\ne", text)
        assertEquals(5, text.indexOf('e'))
    }

    @Test
    fun `layout text remains the canonical chapter coordinate source`() {
        val text = HighlightTextBuilder.build(
            listOf(
                LineInput("—", false),
                LineInput("b", false)
            )
        )

        assertEquals("—b", text)
        assertEquals(1, text.indexOf('b'))
    }
}
