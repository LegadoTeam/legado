package io.legado.app.help.storage

import io.legado.app.data.entities.BookHighlight
import io.legado.app.help.HighlightStyle
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import org.junit.Assert.assertEquals
import org.junit.Test

class HighlightBackupCompatibilityTest {

    @Test
    fun `legacy colors are converted without replacing current styles`() {
        val json = """[{"bgColor": 1, "textColor": 2}]"""
        val legacy = GSON.fromJsonArray<BookHighlight>(json).getOrThrow().single()
        val current = BookHighlight(time = 2).apply {
            applyStyle(HighlightStyle(fill = 7))
        }

        applyLegacyHighlightStyles(json, listOf(legacy))
        applyLegacyHighlightStyles("""[{"bgColor": 3}]""", listOf(current))

        assertEquals(HighlightStyle(fill = 1, textColor = 2), legacy.styleObj())
        assertEquals(BookHighlight.UNKNOWN_TITLE_LENGTH, legacy.layoutTitleLength)
        assertEquals(HighlightStyle(fill = 7), current.styleObj())
    }
}
