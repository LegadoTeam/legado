package io.legado.app.ui.code

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CodeEditorSelectionContractTest {

    @Test
    fun `overflow select all uses the editor document instead of ime extracted text`() {
        val activity = projectFile(
            "app/src/main/java/io/legado/app/ui/code/CodeEditActivity.kt"
        ).readText()
        val menu = projectFile("app/src/main/res/menu/code_edit_activity.xml").readText()

        assertTrue(menu.contains("@+id/menu_select_all"))
        assertTrue(menu.contains("@string/select_all"))
        assertTrue(activity.contains("menu_select_all)?.isVisible = showSoraActions"))
        assertTrue(activity.contains("R.id.menu_select_all -> if (!useSafeEditor) editor.selectAll()"))
        assertTrue(activity.contains("props.maxIPCTextLength = 64 * 1024"))
    }

    private fun projectFile(path: String): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir)) { it.parentFile }
            .map { File(it, path) }
            .first { it.exists() }
    }
}
