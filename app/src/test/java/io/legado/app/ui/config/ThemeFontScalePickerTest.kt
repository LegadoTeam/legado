package io.legado.app.ui.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThemeFontScalePickerTest {

    @Test
    fun `font scale picker keeps the effective configured or system value`() {
        val source = readProjectFile("src/main/java/io/legado/app/ui/config/ThemeConfigFragment.kt")
            .substringAfter("PreferKey.fontScale -> NumberPickerDialog")
            .substringBefore("PreferKey.bgImage ->")

        assertTrue(source.contains("AppContextWrapper.getFontScale(requireContext()) * 10"))
        assertTrue(source.contains(".roundToInt()"))
        assertTrue(source.contains(".coerceIn(8, 16)"))
        assertFalse(source.contains(".setValue(10)"))
    }

    private fun readProjectFile(pathInApp: String): String {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()
    }
}
