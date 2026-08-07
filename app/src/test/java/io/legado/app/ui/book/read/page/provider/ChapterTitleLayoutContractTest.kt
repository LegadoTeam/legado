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
        val titleReview = layout.indexOf("ChapterProvider.appendReviewColumnIfNeeded(", titleLoop)
        val titleBottomSpacing = layout.indexOf("durY += titleBottomSpacing", titleLoop)

        assertTrue(layout.contains("if (splitTitle == null) 1 else 2"))
        assertTrue(layout.contains("titleLines.indexOfFirst { !it.second }"))
        assertTrue(titleImage in 0 until titleLoop)
        assertTrue(layout.contains("lineIndex == titleImageIndex && titleImgText != null"))
        assertTrue(layout.contains("isParagraphEnd = true"))
        assertTrue(titleReview in titleLoop until titleBottomSpacing)
        assertFalse(layout.contains("splitTitle == null ||"))
        assertTrue(provider.contains("isTitle = line.isReviewTitle"))
        assertTrue(provider.contains("isTitle = textLine.isReviewTitle"))
    }

    @Test
    fun `right titles shift only when chapter reviews exist`() {
        val layout = projectFile("TextChapterLayout.kt")

        assertTrue(layout.contains("private val rightTitleHasReview ="))
        assertTrue(layout.contains("private val rightTitleMayHaveReview ="))
        assertTrue(layout.contains("ChapterProvider.hasReviewCountProvider(bookChapter.index)"))
        assertTrue(layout.contains("JsSourceReview.hasReviewCapability(source)"))
        assertTrue(layout.contains("source.ruleReview?.configuredSummaryUrl() != null"))
        assertTrue(layout.contains("if (ChapterProvider.hasReviewCountProvider(bookChapter.index))"))
        assertTrue(
            layout.contains("ChapterProvider.refreshReviewColumns(textPage, bookChapter.index)")
        )
        assertTrue(layout.contains("(visibleWidth - rightTitleReviewInset).toInt().coerceAtLeast(1)"))
        assertTrue(layout.contains("ZhLayout(text, textPaint, textLayoutWidth"))
        assertTrue(layout.contains("StaticLayout(text, textPaint, textLayoutWidth"))
        assertTrue(layout.contains("reviewTrailingInset = if (usesRightTitleReviewInset)"))
        assertTrue(layout.contains("reviewTrailingPadding = paddingRight.toFloat()"))
        assertTrue(
            layout.contains(
                "isReviewTrailingInsetApplied = usesRightTitleReviewInset && rightTitleHasReview"
            )
        )
        val provider = projectFile("ChapterProvider.kt")
        assertTrue(provider.contains("updateReviewTrailingInset(line, titleHasReview)"))
        assertTrue(provider.contains("val nextInset = ReviewColumnGeometry.trailingInset("))
        assertTrue(provider.contains("ReviewColumnGeometry.trailingShift("))
        assertTrue(provider.contains("column.start += delta"))
        assertTrue(provider.contains("column.end += delta"))
    }

    private fun projectFile(name: String): String {
        val relative = "src/main/java/io/legado/app/ui/book/read/page/provider/$name"
        return sequenceOf(File(relative), File("app/$relative"))
            .first(File::isFile)
            .readText()
    }
}
