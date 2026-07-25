package io.legado.app.ui.font

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FontSelectionStyleTest {

    @Test
    fun `selected font uses an accent stroke`() {
        val layout = readProjectFile("src/main/res/layout/item_font.xml")
        val adapter = readProjectFile("src/main/java/io/legado/app/ui/font/FontAdapter.kt")

        assertFalse(layout.contains("MaterialCardView"))
        assertTrue(layout.contains("<LinearLayout"))
        assertTrue(layout.contains("@+id/root_card"))
        assertTrue(layout.contains("android:foreground=\"?android:attr/selectableItemBackground\""))
        assertTrue(adapter.contains("rootCard.background = GradientDrawable().apply"))
        assertTrue(adapter.contains("setColor(Color.TRANSPARENT)"))
        assertTrue(adapter.contains("setStroke(2.dpToPx(), context.accentColor)"))
    }

    @Test
    fun `font preview falls back when loading a recycled row fails`() {
        val adapter = readProjectFile("src/main/java/io/legado/app/ui/font/FontAdapter.kt")
            .substringAfter("override fun convert")

        assertTrue(adapter.contains("tvFont.typeface = kotlin.runCatching"))
        assertTrue(adapter.contains("}.getOrNull() ?: Typeface.DEFAULT"))
    }

    private fun readProjectFile(pathInApp: String): String {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()
    }
}
