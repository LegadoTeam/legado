package io.legado.app.ui.book.read.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NativeReviewRenderingSourceTest {

    @Test
    fun `copy keeps paragraph break before trailing review column`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/book/read/page/ContentTextView.kt"
        ).readText().normalizeLines()
        val selectedTextBlock = source.substringAfter("fun getSelectedText(): String")
            .substringBefore("fun createBookmark()")

        assertTrue(
            selectedTextBlock.contains(
                "val lastTextColumnIndex = textLine.columns.indexOfLast"
            )
        )
        assertEquals(
            2,
            Regex("charIndex == lastTextColumnIndex")
                .findAll(selectedTextBlock)
                .count()
        )
    }

    @Test
    fun `image review has a baseline and bounded hit target`() {
        val layout = projectFile(
            "src/main/java/io/legado/app/ui/book/read/page/provider/TextChapterLayout.kt"
        ).readText().normalizeLines()
        val reviewColumn = projectFile(
            "src/main/java/io/legado/app/ui/book/read/page/entities/column/ReviewColumn.kt"
        ).readText().normalizeLines()
        val contentView = projectFile(
            "src/main/java/io/legado/app/ui/book/read/page/ContentTextView.kt"
        ).readText().normalizeLines()

        assertTrue(layout.contains("textLine.lineBase = textLine.lineBottom"))
        assertTrue(reviewColumn.contains("val extraTouchWidth = if (textLine.isImage)"))
        assertTrue(reviewColumn.contains("localY in (baseLine - height)..baseLine"))
        assertTrue(contentView.contains("it.isTouch(x, y, relativeOffset)"))
        assertTrue(contentView.contains("textColumn !is ReviewColumn"))
    }

    private fun String.normalizeLines(): String = replace("\r\n", "\n")

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
