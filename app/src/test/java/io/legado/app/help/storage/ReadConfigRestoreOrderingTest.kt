package io.legado.app.help.storage

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadConfigRestoreOrderingTest {

    @Test
    fun `restore applies selected reading style before underline normalization`() {
        val restore = projectFile(
            "src/main/java/io/legado/app/help/storage/Restore.kt"
        ).readText()
        val shareConfigIndex = restore.indexOf(
            "ReadBookConfig.initShareConfig(normalizeUnderline = false)"
        )
        val styleIndex = restore.indexOf(
            "readStyleSelect = appCtx.getPrefInt(PreferKey.readStyleSelect)",
            shareConfigIndex,
        )
        val normalizeIndex = restore.indexOf(
            "ReadBookConfig.normalizeUnderlineConfigAfterRestore()",
            styleIndex,
        )

        assertTrue(shareConfigIndex >= 0)
        assertTrue(styleIndex > shareConfigIndex)
        assertTrue(normalizeIndex > styleIndex)

        val config = projectFile(
            "src/main/java/io/legado/app/help/config/ReadBookConfig.kt"
        ).readText()
        assertTrue(config.contains("fun initShareConfig(normalizeUnderline: Boolean = true)"))
        assertTrue(config.contains("if (normalizeUnderline) normalizeUnderlineConfig()"))
    }

    private fun projectFile(pathInApp: String): File =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .first(File::isFile)
}
