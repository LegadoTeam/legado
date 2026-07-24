package io.legado.app.ui.widget.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditSafetyTest {

    @Test
    fun `combining mark bomb is detected`() {
        val bomb = "a" + "\u0301".repeat(12) + "b"

        assertTrue(EditSafety.isCombiningHeavy(bomb))
    }

    @Test
    fun `many scattered combining marks are detected`() {
        val text = buildString { repeat(70) { append('e').append('\u0301') } }

        assertTrue(EditSafety.isCombiningHeavy(text))
    }

    @Test
    fun `combining mark bomb after a long prefix is detected`() {
        val text = "a".repeat(5_000) + "b" + "\u0301".repeat(8)

        assertTrue(EditSafety.isCombiningHeavy(text))
    }

    @Test
    fun `dense scattered marks after a long prefix are detected`() {
        val denseSuffix = buildString { repeat(20) { append('e').append('\u0301') } }

        assertTrue(EditSafety.isCombiningHeavy("a".repeat(5_000) + denseSuffix))
    }

    @Test
    fun `supplementary combining marks are detected`() {
        val combiningMark = String(Character.toChars(0x1D167))

        assertTrue(EditSafety.isCombiningHeavy("note" + combiningMark.repeat(8)))
    }

    @Test
    fun `normal text and a decomposed accent pass`() {
        assertFalse(EditSafety.isCombiningHeavy(""))
        assertFalse(EditSafety.isCombiningHeavy("Cafe\u0301"))
        assertFalse(EditSafety.isCombiningHeavy("@css:.book-item@href##\\d+##<js>result</js>"))
        assertFalse(EditSafety.isCombiningHeavy("var a = 1; // 中文注释"))
    }

    @Test
    fun `many low density decomposed accents pass`() {
        val text = buildString {
            repeat(100) {
                append("ordinary words around Cafe\u0301 with spacing ")
            }
        }

        assertFalse(EditSafety.isCombiningHeavy(text))
    }

    @Test
    fun `emoji and supplementary variation selectors pass`() {
        val supplementarySelector = String(Character.toChars(0xE0100))

        assertFalse(EditSafety.isCombiningHeavy("☯️".repeat(70)))
        assertFalse(EditSafety.isCombiningHeavy(("漢" + supplementarySelector).repeat(70)))
    }

    @Test
    fun `unsafe presentation replaces only the displayed text`() {
        val original = "a" + "\u0301".repeat(12)
        val placeholder = "Tap to edit safely"

        val presentation = EditSafety.presentation(original, placeholder)

        assertEquals(placeholder, presentation.text)
        assertFalse(presentation.isInlineEditable)
        assertEquals("a" + "\u0301".repeat(12), original)
    }

    @Test
    fun `normal presentation remains inline editable`() {
        val original = "@css:.book-item"

        val presentation = EditSafety.presentation(original, "placeholder")

        assertEquals(original, presentation.text)
        assertTrue(presentation.isInlineEditable)
    }

    @Test
    fun `long text is routed away from inline editor`() {
        assertFalse(EditSafety.isTooLongForInline("x".repeat(EditSafety.MAX_INLINE_TEXT_LENGTH)))
        assertTrue(EditSafety.isTooLongForInline("x".repeat(EditSafety.MAX_INLINE_TEXT_LENGTH + 1)))
    }
}
