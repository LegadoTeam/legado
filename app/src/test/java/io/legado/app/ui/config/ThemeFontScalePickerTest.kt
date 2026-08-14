package io.legado.app.ui.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThemeFontScalePickerTest {

    @Test
    fun `font scale picker keeps the configured value`() {
        val source = readProjectFile("src/main/java/io/legado/app/ui/config/ThemeConfigFragment.kt")
            .substringAfter("PreferKey.fontScale -> NumberPickerDialog")
            .substringBefore("PreferKey.bgImage ->")

        assertTrue(source.contains("getPrefInt(PreferKey.fontScale).takeIf { it in 8..16 } ?: 10"))
        assertFalse(source.contains(".setValue(10)"))
    }

    private fun readProjectFile(pathInApp: String): String {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()
    }
}
