package io.legado.app.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HighlightAnchorTest {

    @Test
    fun `unchanged text keeps the saved range`() {
        assertEquals(
            HighlightAnchor.Anchor(4, 8),
            HighlightAnchor.reanchor("zero-one-two", 4, 8, "-one")
        )
    }

    @Test
    fun `edited text uses a unique matching occurrence`() {
        assertEquals(0, HighlightAnchor.jumpPos("main text", 4, "main"))
        assertEquals(
            HighlightAnchor.Anchor(0, 4),
            HighlightAnchor.reanchor("main text", 4, 8, "main")
        )
        assertEquals(
            HighlightAnchor.Anchor(4, 8),
            HighlightAnchor.reanchor("same xx same", 4, 8, "same")
        )
        assertEquals(
            HighlightAnchor.Anchor(9, 13),
            HighlightAnchor.reanchor("same xx same", 9, 13, "same")
        )
        assertEquals(
            HighlightAnchor.Anchor(99, 103),
            HighlightAnchor.reanchor("same xx same", 99, 103, "same")
        )
    }

    @Test
    fun `page start follows content inserted or removed before it`() {
        val pageText = "current page starts here and remains unique"
        val oldText = "old introduction\n$pageText"
        val oldPosition = oldText.indexOf(pageText)
        val anchorText = oldText.drop(oldPosition).take(64)
        val insertedText = "new preface\n$oldText"
        val removedText = pageText

        assertEquals(
            insertedText.indexOf(pageText),
            HighlightAnchor.jumpPos(insertedText, oldPosition, anchorText)
        )
        assertEquals(
            removedText.indexOf(pageText),
            HighlightAnchor.jumpPos(removedText, oldPosition, anchorText)
        )
    }

    @Test
    fun `missing source hides ranges but keeps jump fallback`() {
        assertNull(HighlightAnchor.reanchor("changed", 3, 7, "gone"))
        assertEquals(3, HighlightAnchor.jumpPos("changed", 3, "gone"))
        assertEquals(
            HighlightAnchor.Anchor(3, 7),
            HighlightAnchor.reanchor("changed", 3, 7, "")
        )
        assertEquals(
            HighlightAnchor.Anchor(3, 8),
            HighlightAnchor.reanchor("changed", 3, 8, "gone")
        )
    }
}
