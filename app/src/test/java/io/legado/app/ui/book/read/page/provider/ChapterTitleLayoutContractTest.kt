package io.legado.app.ui.book.read.page.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChapterTitleLayoutContractTest {

    @Test
    fun `split titles preserve text positions and review ownership`() {
        val layout = projectFile("TextChapterLayout.kt")
        val provider = projectFile("ChapterProvider.kt")
        val titleImage = layout.indexOf("else -> setTypeImage(", layout.indexOf("val titleImg ="))
        val titleLoop = layout.indexOf("titleLines.forEachIndexed")

        assertTrue(layout.contains("if (splitTitle == null) 1 else 2"))
        assertTrue(layout.contains("titleLines.indexOfFirst { !it.second }"))
        assertTrue(titleImage in 0 until titleLoop)
        assertTrue(layout.contains("lineIndex == titleImageIndex && titleImgText != null"))
        assertTrue(layout.contains("isParagraphEnd = true"))
        assertFalse(layout.contains("splitTitle == null ||"))
        assertTrue(provider.contains("isTitle = line.isReviewTitle"))
        assertTrue(provider.contains("isTitle = textLine.isReviewTitle"))
    }

    @Test
    fun `right titles reserve space only for existing chapter reviews`() {
        val layout = projectFile("TextChapterLayout.kt")
        val activity = sequenceOf(
            File("src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"),
            File("app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"),
        ).first(File::isFile).readText()

        assertTrue(layout.contains("isRightTitle && ChapterProvider.getReviewCount("))
        assertTrue(layout.contains("(visibleWidth - rightTitleReviewInset).toInt().coerceAtLeast(1)"))
        assertTrue(layout.contains("ZhLayout(text, textPaint, textLayoutWidth"))
        assertTrue(layout.contains("StaticLayout(text, textPaint, textLayoutWidth"))
        assertTrue(activity.contains("ReadBookConfig.isRightTitle && (result.counts[-1] ?: 0) > 0"))
        assertTrue(activity.contains("ReadBook.curTextChapter = null"))
        assertTrue(activity.contains("ReadBook.loadContent(chapterIndex, resetPageOffset = false)"))
    }

    private fun projectFile(name: String): String {
        val relative = "src/main/java/io/legado/app/ui/book/read/page/provider/$name"
        return sequenceOf(File(relative), File("app/$relative"))
            .first(File::isFile)
            .readText()
    }
}
