package io.legado.app.ui.config

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupDefaultPathContractTest {

    @Test
    fun `default backup path is selectable and used directly`() {
        val source = source("app/src/main/java/io/legado/app/ui/config/BackupConfigFragment.kt")
        val selector = source.substringAfter("private fun showBackupPathSelector()")
            .substringBefore("private fun lanBackupTransfer()")
        assertTrue(selector.contains("getString(R.string.default_path)"))
        assertTrue(selector.contains("getString(R.string.select_folder)"))
        assertTrue(selector.contains("0 -> AppConfig.backupPath = null"))
        assertTrue(selector.contains("1 -> selectBackupPath.launch()"))

        val click = source.substringAfter("override fun onPreferenceTreeClick")
            .substringBefore("private fun showBackupPathSelector()")
        assertTrue(click.contains("PreferKey.backupPath -> showBackupPathSelector()"))

        val summary = source.substringAfter("private fun upPreferenceSummary")
            .substringBefore("override fun onPreferenceTreeClick")
        assertTrue(
            summary.contains(
                "value?.takeIf { it.isNotBlank() } ?: getString(R.string.default_path)"
            )
        )

        val backup = source.substringAfter("fun backup()")
            .substringBefore("private fun backupUsePermission")
        assertTrue(backup.contains("if (backupPath.isNullOrEmpty())"))
        assertTrue(backup.contains("backup(null)"))
        assertTrue(source.contains("private fun backup(backupPath: String?)"))
    }

    private fun source(relativePath: String): String {
        return File(repositoryRoot(), relativePath).readText()
    }

    private fun repositoryRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }
}
