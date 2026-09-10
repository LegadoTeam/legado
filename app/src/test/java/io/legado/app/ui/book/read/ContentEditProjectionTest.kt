package io.legado.app.ui.book.read

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentEditProjectionTest {
    @Test fun editsKeepHiddenMarkupAndMapVisiblePositions() {
        val image = "<img src=\"https://example.org/image.png\">"
        val bubble = """<img src="data:image/svg+xml;base64,QQ,{'style':'TEXT','click':'getDP(1)'}">"""
        val raw = "甲${image}${bubble}乙\n丙"
        val projection = ContentEditProjection(raw)
        assertEquals("甲乙\n丙", projection.text)
        for (offset in 0..projection.text.length) {
            assertEquals(offset, projection.displayOffset(projection.rawOffset(offset)))
            assertEquals(offset, projection.displayOffset(projection.rawOffset(offset, false)))
        }
        for (start in 0..projection.text.length) for (end in start..projection.text.length) {
            val edited = projection.replace(start, end, "新文")
            assertTrue(edited.contains(image + bubble))
            assertEquals(projection.text.replaceRange(start, end, "新文"), ContentEditProjection(edited).text)
        }
        assertEquals(image + bubble, ContentEditProjection(image + bubble).replace(0, 0, ""))
        assertEquals("1 < 2，普通 <内容> 与 &amp;", ContentEditProjection("1 < 2，普通 <内容> 与 &amp;").text)
    }
}
