package io.legado.app.data.entities

import io.legado.app.help.HighlightStyle
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookHighlightTest {

    @Test
    fun `style is serialized and restored`() {
        val highlight = BookHighlight()
        val style = HighlightStyle(fill = 0x80FFF176.toInt(), bold = true)
        highlight.applyStyle(style)

        assertTrue(highlight.style.isNotBlank())
        assertEquals(style, highlight.styleObj())
        assertEquals(style, highlight.copy().styleObj())
    }

    @Test
    fun `direct JSON replacement invalidates parsed style cache`() {
        val highlight = BookHighlight()
        highlight.applyStyle(HighlightStyle(fill = 1))
        assertEquals(1, highlight.styleObj().fill)

        highlight.style = GSON.toJson(HighlightStyle(fill = 2))
        assertEquals(2, highlight.styleObj().fill)
    }

    @Test
    fun `oversized shadow remains editable and serializable`() {
        val highlight = BookHighlight(style = """{"shadow":{"radius":3.5e38}}""")

        val restored = highlight.styleObj()
        assertTrue(restored.shadow?.radius?.isFinite() == true)
        highlight.applyStyle(restored)

        assertEquals(
            restored,
            GSON.fromJsonObject<HighlightStyle>(highlight.style).getOrThrow()
        )
    }

    @Test
    fun `legacy owner binding fills only missing stable urls`() {
        val legacy = BookHighlight()
        legacy.bindLegacyOwner("book-url", "chapter-url")
        assertEquals("book-url", legacy.bookUrl)
        assertEquals("chapter-url", legacy.chapterUrl)

        val current = BookHighlight(bookUrl = "current-book", chapterUrl = "current-chapter")
        current.bindLegacyOwner("other-book", "other-chapter")
        assertEquals("current-book", current.bookUrl)
        assertEquals("current-chapter", current.chapterUrl)
    }

    @Test
    fun `known title length keeps body positions stable after title changes`() {
        val highlight = BookHighlight(
            chapterPos = 15,
            chapterPosEnd = 18,
            layoutTitleLength = 5
        )

        assertEquals(10, highlight.bodyStart(currentTitleLength = 9))
        assertEquals(13, highlight.bodyEnd(currentTitleLength = 9))
    }

    @Test
    fun `legacy title length is pinned once before later layout changes`() {
        val highlight = BookHighlight(chapterPos = 15, chapterPosEnd = 18)

        assertFalse(highlight.pinLayoutTitleLength(currentTitleLength = -1))
        assertEquals(10, highlight.bodyStart(currentTitleLength = 5))
        assertEquals(13, highlight.bodyEnd(currentTitleLength = 5))
        assertTrue(highlight.pinLayoutTitleLength(currentTitleLength = 5))
        assertEquals(10, highlight.bodyStart(currentTitleLength = 9))
        assertEquals(13, highlight.bodyEnd(currentTitleLength = 9))
        assertFalse(highlight.pinLayoutTitleLength(currentTitleLength = 9))
        assertEquals(5, highlight.layoutTitleLength)
    }
}
