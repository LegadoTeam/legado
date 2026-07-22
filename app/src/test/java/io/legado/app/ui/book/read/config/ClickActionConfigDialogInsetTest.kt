package io.legado.app.ui.book.read.config

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClickActionConfigDialogInsetTest {

    @Test
    fun `click action dialog applies navigation padding before binding content`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/config/ClickActionConfigDialog.kt"
        )
        val applyPadding = "binding.rootView.applyNavigationBarPadding(withInitialPadding = true)"

        assertTrue(source.contains("import io.legado.app.utils.applyNavigationBarPadding"))
        assertTrue(source.contains(applyPadding))
        assertTrue(source.indexOf(applyPadding) < source.indexOf("initData()"))
    }

    @Test
    fun `click action dialog keeps a padded full screen root`() {
        val layout = readProjectFile("src/main/res/layout/dialog_click_action_config.xml")

        assertTrue(layout.contains("android:id=\"@+id/root_view\""))
        assertTrue(layout.contains("android:layout_width=\"match_parent\""))
        assertTrue(layout.contains("android:layout_height=\"match_parent\""))
        assertTrue(layout.contains("android:padding=\"3dp\""))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
