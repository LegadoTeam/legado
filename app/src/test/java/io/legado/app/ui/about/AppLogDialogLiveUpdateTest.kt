package io.legado.app.ui.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppLogDialogLiveUpdateTest {

    private val root = repositoryRoot()
    private val appLog = source("app/src/main/java/io/legado/app/constant/AppLog.kt")
    private val dialog = source("app/src/main/java/io/legado/app/ui/about/AppLogDialog.kt")

    @Test
    fun `saved logs invalidate the visible dialog snapshot`() {
        assertTrue(appLog.contains("MutableSharedFlow<Unit>(extraBufferCapacity = 1)"))
        assertEquals(
            2,
            Regex("logUpdates\\.tryEmit\\(Unit\\)")
                .findAll(appLog)
                .count(),
        )
        assertTrue(dialog.contains("repeatOnLifecycle(STARTED)"))
        assertTrue(dialog.contains("AppLog.updates.collect"))
        val observer = dialog.substringAfter("AppLog.updates.collect")
            .substringBefore("}")
        assertTrue(observer.contains("adapter.setItems(AppLog.logs)"))
    }

    @Test
    fun `clearing logs requires confirmation and clears HTTP records too`() {
        val clearAction = dialog.substringAfter("R.id.menu_clear")
            .substringBefore("return true")

        assertTrue(clearAction.contains("alert(R.string.clear, R.string.clear_log_confirm)"))
        assertTrue(clearAction.contains("AppLog.clear()"))
        assertTrue(clearAction.contains("HttpLogStore.clear()"))
    }

    private fun source(path: String): String = File(root, path).readText()

    private fun repositoryRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }
}
