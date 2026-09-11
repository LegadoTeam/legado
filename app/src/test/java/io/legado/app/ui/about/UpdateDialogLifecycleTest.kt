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
        val shared = projectFile(
            "src/main/java/io/legado/app/ui/about/CheckAppUpdate.kt"
        ).readText()

        assertGuardBeforeDialog(main, "supportFragmentManager.isStateSaved")
        assertGuardBeforeDialog(shared, "isAdded && !childFragmentManager.isStateSaved")
        for (path in listOf("ui/about/AboutFragment.kt", "ui/main/my/MyFragment.kt")) {
            val caller = projectFile("src/main/java/io/legado/app/$path").readText()
            assertTrue(caller.contains("\"check_update\" -> checkAppUpdate()"))
            assertTrue(caller.contains("\"check_beta_update\" -> checkAppUpdate(beta = true)"))
        }
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
    fun `update dialog uses the system download action for beta and formal updates`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/about/UpdateDialog.kt"
        ).readText()

        assertTrue(source.contains("putBoolean(\"isBeta\", updateInfo.isBeta)"))
        assertTrue(source.contains("binding.betaActions.isVisible = true"))
        assertTrue(source.contains("setLayout(0.9f, 0.8f)"))
        assertTrue(source.contains("binding.btnBetaUpdate.setText(R.string.beta_update_now)"))
        assertTrue(source.contains("startDownload(arguments?.getString(\"url\"))"))
        assertFalse(
            source.substringAfter("binding.btnBetaUpdate.setOnClickListener")
                .substringBefore("if (!isBetaUpdate)")
                .contains("openUrl")
        )
    }

    @Test
    fun `formal update hides only the primary toolbar download action`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/about/UpdateDialog.kt"
        ).readText()

        assertTrue(source.contains("binding.toolBar.menu.findItem(R.id.menu_download).isVisible = false"))
        assertTrue(source.contains("R.id.menu_download_backup).isVisible"))
        assertTrue(source.contains("R.id.menu_download_mirror).isVisible"))
        assertTrue(source.contains("R.id.menu_download_alternate_mirror).isVisible"))
    }

    @Test
    fun `formal update dialog exposes the backup cdn separately from github`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/about/UpdateDialog.kt"
        ).readText()

        assertTrue(source.contains("putString(\"mirrorUrl\", updateInfo.mirrorDownloadUrl)"))
        assertTrue(
            source.contains(
                "putString(\"alternateMirrorUrl\", updateInfo.alternateMirrorDownloadUrl)"
            )
        )
        assertTrue(source.contains("R.id.menu_download_mirror).isVisible"))
        assertTrue(
            source.contains(
                "R.id.menu_download_mirror -> startDownload(arguments?.getString(\"mirrorUrl\"))"
            )
        )
        assertTrue(source.contains("R.id.menu_download_alternate_mirror).isVisible"))
        assertTrue(
            source.contains(
                "R.id.menu_download_alternate_mirror ->"
            ) && source.contains("startDownload(arguments?.getString(\"alternateMirrorUrl\"))")
        )
        assertTrue(source.contains("R.id.menu_download_backup -> startDownload"))
        assertTrue(
            projectFile("src/main/res/menu/app_update.xml")
                .readText()
                .contains("android:id=\"@+id/menu_download_mirror\"")
        )
        assertTrue(
            projectFile("src/main/res/menu/app_update.xml")
                .readText()
                .contains("android:id=\"@+id/menu_download_alternate_mirror\"")
        )
    }

    @Test
    fun `manual update errors show their message without a redundant action prefix`() {
        val shared = projectFile(
            "src/main/java/io/legado/app/ui/about/CheckAppUpdate.kt"
        ).readText()
        assertTrue(shared.contains("AppUpdate.checkBeta(lifecycleScope)"))
        assertTrue(shared.contains("AppUpdate.gitHubUpdate.check(lifecycleScope)"))
        assertTrue(shared.contains("appCtx.toastOnUi(it.localizedMessage)"))
        assertFalse(shared.contains("getString(R.string.check_update)"))
        assertFalse(shared.contains("getString(R.string.check_beta_update)"))
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
