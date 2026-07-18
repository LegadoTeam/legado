package io.legado.app.ui.book.read.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TipConfigDialogThemeTest {

    @Test
    fun `template placeholders do not construct material chip with appcompat context`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/config/TipConfigDialog.kt"
        )

        assertFalse(source.contains("Chip(requireContext())"))
        assertTrue(source.contains("AccentBgTextView(requireContext())"))
        assertTrue(source.contains("setRadius(4)"))
        assertTrue(source.contains("chipPlaceholders.addView(placeholderView)"))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
