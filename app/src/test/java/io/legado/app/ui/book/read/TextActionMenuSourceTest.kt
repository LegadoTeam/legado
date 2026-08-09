package io.legado.app.ui.book.read

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TextActionMenuSourceTest {

    @Test
    fun `selection popup stays bottom anchored above selected text`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/book/read/TextActionMenu.kt"
        ).readText()
        val show = source.substringAfter("fun show(")
            .substringBefore("inner class Adapter")

        assertTrue(show.contains("Gravity.BOTTOM or Gravity.START"))
        assertTrue(show.contains("windowHeight - startTopY"))
        assertFalse(show.contains("moreMenuItems.isEmpty()"))
        assertFalse(show.contains("contentView.measure("))
        assertFalse(show.contains("contentView.measuredHeight"))
    }

    private fun projectFile(pathInApp: String): File {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?: error("Project file not found: $pathInApp")
    }
}
