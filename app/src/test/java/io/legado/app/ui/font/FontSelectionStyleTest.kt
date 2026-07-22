package io.legado.app.ui.font

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FontSelectionStyleTest {

    @Test
    fun `selected font uses an accent stroke`() {
        val layout = readProjectFile("src/main/res/layout/item_font.xml")
        val adapter = readProjectFile("src/main/java/io/legado/app/ui/font/FontAdapter.kt")

        assertTrue(layout.contains("MaterialCardView"))
        assertTrue(layout.contains("@+id/root_card"))
        assertTrue(adapter.contains("strokeWidth = if (selected) 2.dpToPx() else 0"))
        assertTrue(adapter.contains("setStrokeColor(context.accentColor)"))
    }

    private fun readProjectFile(pathInApp: String): String {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()
    }
}
