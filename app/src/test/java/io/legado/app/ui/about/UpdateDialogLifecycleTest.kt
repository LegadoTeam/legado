package io.legado.app.ui.about

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateDialogLifecycleTest {

    @Test
    fun `update callbacks skip dialogs after fragment state is saved`() {
        val main = functionBody(
            "src/main/java/io/legado/app/ui/main/MainActivity.kt",
            "private suspend fun upVersion()",
            "private suspend fun setLocalPassword()"
        )
        val about = functionBody(
            "src/main/java/io/legado/app/ui/about/AboutFragment.kt",
            "private fun checkUpdate()",
            "private fun joinQQGroup("
        )

        assertGuardBeforeDialog(main, "supportFragmentManager.isStateSaved")
        assertGuardBeforeDialog(about, "childFragmentManager.isStateSaved")
    }

    private fun assertGuardBeforeDialog(source: String, guard: String) {
        assertTrue(source.indexOf(guard) in 0 until source.indexOf("UpdateDialog(it)"))
    }

    private fun functionBody(path: String, start: String, end: String): String {
        return projectFile(path).readText().substringAfter(start).substringBefore(end)
    }

    private fun projectFile(pathInApp: String): File {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?: error("Project file not found: $pathInApp")
    }
}
