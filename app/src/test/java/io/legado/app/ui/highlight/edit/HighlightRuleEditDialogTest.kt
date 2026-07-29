package io.legado.app.ui.highlight.edit

import io.legado.app.help.HighlightColors
import io.legado.app.help.HighlightStyles
import io.legado.app.ui.book.read.HighlightStyleDialog
import io.legado.app.utils.GSON
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HighlightRuleEditDialogTest {

    @Test
    fun `color picker uses the requested channel presets`() {
        val textConfig = HighlightRuleEditDialog.colorPickerConfig(
            HighlightStyleDialog.HL_TEXT,
            initial = 0,
            withAlpha = false
        )
        val fillConfig = HighlightRuleEditDialog.colorPickerConfig(
            HighlightStyleDialog.HL_FILL,
            initial = 0,
            withAlpha = true
        )

        assertEquals(HighlightColors.text.first(), textConfig.color)
        assertArrayEquals(HighlightColors.text, textConfig.presets)
        assertEquals(HighlightColors.bg.first(), fillConfig.color)
        assertArrayEquals(HighlightColors.bg, fillConfig.presets)
    }

    @Test
    fun `color picker keeps an existing color`() {
        val color = 0xFF123456.toInt()

        val config = HighlightRuleEditDialog.colorPickerConfig(
            HighlightStyleDialog.HL_FILL,
            color,
            withAlpha = true
        )

        assertEquals(color, config.color)
    }

    @Test
    fun `new rules default to the first visible highlight preset`() {
        assertEquals(
            GSON.toJson(HighlightStyles.presets.first()),
            HighlightRuleEditDialog.initialStyle(null)
        )
    }

    @Test
    fun `new rules keep a supplied source highlight style`() {
        val sourceStyle = "{\"fill\":1}"

        assertEquals(sourceStyle, HighlightRuleEditDialog.initialStyle(sourceStyle))
    }

    @Test
    fun `rule preview uses the shared fill renderer`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/highlight/edit/HighlightRuleEditDialog.kt"
        ).readText()

        assertTrue(source.contains("HighlightFillPreviewDrawable"))
        assertTrue(!source.contains("setBackgroundColor(editingStyle.fill)"))
    }

    @Test
    fun `rule creation preserves the source manual highlight`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/highlight/edit/HighlightRuleEditDialog.kt"
        ).readText()

        assertTrue(!source.contains("ReadBook.removeHighlight"))
        assertTrue(!source.contains("sourceHighlightTime"))
    }

    @Test
    fun `save ignores repeated confirmation while insertion is running`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/highlight/edit/HighlightRuleEditDialog.kt"
        ).readText()

        assertTrue(source.contains("if (!isLoaded || isSaving) return"))
        assertTrue(source.contains("isSaving = true"))
        assertTrue(source.contains("finally {"))
        assertTrue(source.contains("isSaving = false"))
    }

    @Test
    fun `existing rule cannot save an empty state before loading`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/highlight/edit/HighlightRuleEditDialog.kt"
        ).readText()

        assertTrue(source.contains("private var isLoaded = false"))
        assertTrue(source.contains("binding.btnOk.isEnabled = false"))
        assertTrue(source.contains("if (isLoaded) (if (view == null) rule else getRule())"))
    }

    private fun projectFile(pathInApp: String): File =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .first(File::isFile)
}
