package io.legado.app.ui.book.read

import org.junit.Assert.assertEquals
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

    @Test
    fun `reader popups use the popup window coordinate height`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"
        ).readText()

        assertEquals(
            2,
            Regex("binding\\.root\\.rootView\\.height")
                .findAll(source).count()
        )
        assertFalse(source.contains("binding.navigationBar.height"))
        assertFalse(source.contains("binding.root.height +"))
    }

    private fun projectFile(pathInApp: String): File {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?: error("Project file not found: $pathInApp")
    }
}
