package io.legado.app.ui.about

import org.junit.Assert.assertFalse
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
            "private fun checkBetaUpdate()"
        )
        val beta = functionBody(
            "src/main/java/io/legado/app/ui/about/AboutFragment.kt",
            "private fun checkBetaUpdate()",
            "private fun joinQQGroup("
        )

        assertGuardBeforeDialog(main, "supportFragmentManager.isStateSaved")
        assertGuardBeforeDialog(about, "childFragmentManager.isStateSaved")
        assertGuardBeforeDialog(beta, "childFragmentManager.isStateSaved")
    }

    @Test
    fun `update dialog carries release metadata into the toolbar`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/about/UpdateDialog.kt"
        ).readText()

        assertTrue(source.contains("putLong(\"size\", updateInfo.size)"))
        assertTrue(source.contains("putLong(\"createdAt\", updateInfo.createdAt)"))
        assertTrue(source.contains("ConvertUtils.formatFileSize(size)"))
        assertTrue(source.contains("DateTimeFormatter.ISO_LOCAL_DATE"))
    }

    @Test
    fun `beta update dialog opens the selected github asset in the browser`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/about/UpdateDialog.kt"
        ).readText()
        val betaBranch = source
            .substringAfter("binding.betaActions.isVisible = isBetaUpdate")
            .substringBefore("} else {")

        assertTrue(source.contains("putBoolean(\"isBeta\", updateInfo.isBeta)"))
        assertTrue(betaBranch.contains("requireContext().openUrl(url)"))
        assertTrue(!betaBranch.contains("startDownload("))
    }

    @Test
    fun `manual update errors show their message without a redundant action prefix`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/about/AboutFragment.kt"
        ).readText()
        val official = source.substringAfter("private fun checkUpdate()")
            .substringBefore("private fun checkBetaUpdate()")
        val beta = source.substringAfter("private fun checkBetaUpdate()")
            .substringBefore("private fun joinQQGroup(")

        assertTrue(official.contains("appCtx.toastOnUi(it.localizedMessage)"))
        assertFalse(official.contains("getString(R.string.check_update)"))
        assertTrue(beta.contains("appCtx.toastOnUi(it.localizedMessage)"))
        assertFalse(beta.contains("getString(R.string.check_beta_update)"))
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
