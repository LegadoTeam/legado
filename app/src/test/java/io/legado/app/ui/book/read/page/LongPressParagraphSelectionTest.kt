package io.legado.app.ui.book.read.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LongPressParagraphSelectionTest {

    @Test
    fun `paragraph range follows visible page boundaries`() {
        assertEquals(1..3, visibleParagraphRange(2, 6) { it == 0 || it == 3 })
        assertEquals(0..2, visibleParagraphRange(0, 5) { it == 2 })
        assertEquals(2..4, visibleParagraphRange(3, 5) { it == 1 })
        assertEquals(2..2, visibleParagraphRange(2, 5) { it == 1 || it == 2 })
        assertEquals(1..2, selectableParagraphRange(0..3) { it in 1..2 })
        assertEquals(null, selectableParagraphRange(0..1) { false })
    }

    @Test
    fun `paragraph selection is optional and uses the current page`() {
        val preferKey = source("app/src/main/java/io/legado/app/constant/PreferKey.kt")
        val appConfig = source("app/src/main/java/io/legado/app/help/config/AppConfig.kt")
        val readView = source("app/src/main/java/io/legado/app/ui/book/read/page/ReadView.kt")
        val preferences = source("app/src/main/res/xml/pref_config_read.xml")

        assertTrue(preferKey.contains("const val longPressSelectParagraph"))
        assertTrue(
            appConfig.contains(
                "getPrefBoolean(PreferKey.longPressSelectParagraph, false)"
            )
        )
        assertTrue(preferences.contains("android:key=\"longPressSelectParagraph\""))
        assertTrue(preferences.contains("android:defaultValue=\"false\""))
        assertTrue(readView.contains("if (AppConfig.longPressSelectParagraph)"))
        assertTrue(readView.contains("visibleParagraphRange("))
        assertTrue(readView.contains("selectableParagraphRange("))
        assertTrue(readView.contains("columns.isNotEmpty()"))
        assertTrue(readView.contains("startPos.columnIndex = 0"))
        assertTrue(readView.contains("columns.lastIndex"))
        assertTrue(readView.contains("boundary.setText(stringBuilder.toString())"))
    }

    private fun source(relativePath: String): String {
        var current = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            val candidate = File(current, relativePath)
            if (candidate.isFile) return candidate.readText()
            current = current.parentFile ?: return@repeat
        }
        error("Project file not found: $relativePath")
    }
}
