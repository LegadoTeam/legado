package io.legado.app.ui.autoTask

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AutoTaskEditActivitySourceTest {

    @Test
    fun `unsaved edits prompt only after a task has been bound`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/autoTask/AutoTaskEditActivity.kt"
        ).readText().replace("\r\n", "\n")
        val bindBlock = source.substringAfter("private fun bind(")
            .substringBefore("private fun save(")
        val saveBlock = source.substringAfter("private fun save(")
            .substringBefore("private fun buildRule(")
        val finishBlock = source.substringAfter("override fun finish()")
            .substringBefore("private fun textOrNull(")

        assertTrue(bindBlock.contains("originTask = buildDraft()"))
        assertTrue(saveBlock.contains("originTask = buildDraft()"))
        assertTrue(finishBlock.contains("originTask?.let { it != buildDraft() } == true"))
        assertTrue(finishBlock.contains("setMessage(R.string.exit_no_save)"))
        assertTrue(finishBlock.contains("super.finish()"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
