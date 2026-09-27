package io.legado.app.ui.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TitleBarAccessibilityTest {

    @Test
    fun `back navigation uses the localized app label`() {
        val titleBar = projectFile(
            "src/main/java/io/legado/app/ui/widget/TitleBar.kt"
        ).readText()
        assertTrue(titleBar.contains("?: context.getText(R.string.back)"))
        assertTrue(
            titleBar.contains(
                "setHomeActionContentDescription(navigationDescription)"
            )
        )

        val changeSourceNavigation = projectFile(
            "src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceDialog.kt"
        ).readText()
            .substringAfter("private fun initNavigationView()")
            .substringBefore("\n    private fun")
        assertTrue(changeSourceNavigation.contains("R.string.back"))
        assertFalse(changeSourceNavigation.contains("abc_action_bar_up_description"))

        val reviewDetail = projectFile(
            "src/main/java/io/legado/app/ui/book/read/ReviewDetailDialog.kt"
        ).readText()
        assertTrue(
            reviewDetail.contains("setNavigationContentDescription(R.string.close)")
        )
    }

    private fun projectFile(path: String): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val appDir = generateSequence(File(userDir)) { it.parentFile }
            .map { File(it, "app") }
            .first(File::isDirectory)
        return File(appDir, path)
    }
}
