package io.legado.app.ui.book.import

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImportSelectionCountResetTest {

    @Test
    fun `successful imports reset selection through adapter callback`() {
        val localAction = mainAction(
            projectFile("src/main/java/io/legado/app/ui/book/import/local/ImportBookActivity.kt"),
            "\n    private fun alertDirectoryGroup",
        )
        val remoteAction = mainAction(
            projectFile("src/main/java/io/legado/app/ui/book/import/remote/RemoteBookActivity.kt")
        )

        listOf(localAction, remoteAction).forEach { action ->
            assertTrue(action.contains("adapter.selectAll(false)"))
            assertFalse(action.contains("adapter.selected.clear()"))
            assertFalse(action.contains("adapter.notifyDataSetChanged()"))
        }
    }

    private fun mainAction(file: File, endMarker: String = "\n    private fun"): String {
        val source = file.readText()
        val start = source.indexOf("override fun onClickSelectBarMainAction()")
        val end = source.indexOf(endMarker, start)
        assertTrue(start >= 0)
        assertTrue(end > start)
        return source.substring(start, end)
    }

    private fun projectFile(pathInApp: String): File =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?: error("Missing project file: $pathInApp")
}
