package io.legado.app.ui.menu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileManagePopupActionMigrationTest {

    @Test
    fun `file deletion uses the shared vertical popup action menu`() {
        val source = readProjectFile(FILE_MANAGE)

        assertFalse(source.contains("import android.widget.PopupMenu"))
        assertFalse(source.contains("PopupMenu(context, view)"))
        assertContains(source, "popupActionMenu(context)")
        assertContains(source, "item(context.getString(R.string.delete), \"delete\")")
        assertContains(source, "danger(\"delete\")")
        assertContains(source, "\"delete\" -> viewModel.delFile(file)")
    }

    @Test
    fun `parent directory remains excluded from the deletion menu`() {
        val source = readProjectFile(FILE_MANAGE)
        val longClick = source.indexOf("binding.root.setOnLongClickListener")
        val guard = source.indexOf("if (item == viewModel.lastDir)", longClick + 1)
        val guardReturn = source.indexOf("return@setOnLongClickListener true", guard + 1)
        val menu = source.indexOf("showFileMenu(view, item)", guard + 1)

        assertTrue("file rows should define a long-click listener", longClick >= 0)
        assertTrue("parent directory guard should remain before the menu call", guard >= 0)
        assertTrue("parent directory guard should consume the long click", guardReturn in (guard + 1)..<menu)
        assertTrue("file menu should only be shown after the parent directory guard", menu > guard)
    }

    @Test
    fun `legacy file deletion menu resource is removed`() {
        assertFalse(projectFileExists("src/main/res/menu/file_long_click.xml"))
    }

    private fun assertContains(source: String, expected: String) {
        assertTrue("FileManageActivity.kt should contain $expected", source.contains(expected))
    }

    private fun readProjectFile(pathInApp: String): String =
        projectFile(pathInApp)?.readText().orEmpty()

    private fun projectFileExists(pathInApp: String): Boolean =
        projectFile(pathInApp) != null

    private fun projectFile(pathInApp: String): File? =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)

    private companion object {
        const val FILE_MANAGE = "src/main/java/io/legado/app/ui/file/FileManageActivity.kt"
    }
}
