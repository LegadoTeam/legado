package io.legado.app.ui.book.read.page.provider

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NativeReviewProviderSourceTest {

    @Test
    fun `summary providers stay scoped to their chapter`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"
        ).readText().normalizeLines()
        val applyBlock = source.substringAfter("private fun applyReviewSummary(")
            .substringBefore("private fun prefetchAdjacentReviewSummary(")

        assertTrue(
            Regex(
                """if\s*\(targetChapterIndex == chapterIndex\)\s*""" +
                    """result\.counts\[reviewId]\s*\?:\s*0\s*else\s*0"""
            ).containsMatchIn(applyBlock)
        )
        assertTrue(
            Regex(
                """if\s*\(targetChapterIndex == chapterIndex\)\s*""" +
                    """result\.keys\[reviewId]\s*else\s*null"""
            ).containsMatchIn(applyBlock)
        )
    }

    @Test
    fun `rendering and clicks use the same title offset`() {
        val provider = projectFile(
            "src/main/java/io/legado/app/ui/book/read/page/provider/ChapterProvider.kt"
        ).readText().normalizeLines()
        val contentView = projectFile(
            "src/main/java/io/legado/app/ui/book/read/page/ContentTextView.kt"
        ).readText().normalizeLines()
        val layout = projectFile(
            "src/main/java/io/legado/app/ui/book/read/page/provider/TextChapterLayout.kt"
        ).readText().normalizeLines()
        val refreshBlock = provider.substringAfter(
            "private fun refreshReviewColumns(textChapter: TextChapter?)"
        ).substringBefore("fun getReviewCount(")

        assertTrue(refreshBlock.contains("val chapterIndex = textChapter.chapter.index"))
        assertTrue(refreshBlock.contains("chapterIndex = chapterIndex"))
        assertTrue(provider.contains("titleOffset = line.reviewTitleOffset"))
        assertTrue(provider.contains("val reviewId = paragraphNum - titleOffset"))
        assertTrue(contentView.contains("textLine.paragraphNum - textLine.reviewTitleOffset"))
        assertTrue(
            Regex(
                """callBack\.onReviewClick\(\s*resolveReviewId\(textLine\),\s*""" +
                    """column\.count,\s*textPage\.chapterIndex\s*\)"""
            ).containsMatchIn(contentView)
        )
        assertTrue(
            contentView.contains(
                "fun onReviewClick(paragraphNum: Int, count: Int, chapterIndex: Int)"
            )
        )
        assertTrue(
            layout.contains(
                "titleMode != 2 || bookChapter.isVolume || !textChapter.hasBodyContent"
            )
        )
        assertTrue(layout.contains("reviewTitleOffset = reviewTitleOffset"))
    }

    @Test
    fun `review overflow remains inside the screen clip`() {
        val provider = projectFile(
            "src/main/java/io/legado/app/ui/book/read/page/provider/ChapterProvider.kt"
        ).readText().normalizeLines()
        val layoutBlock = provider.substringAfter("fun upLayout()")
            .substringBefore("private fun setFallbackLayout()")
        val visibleRectBlock = layoutBlock.substringAfter("visibleRect.set(")

        assertTrue(visibleRectBlock.contains("viewWidth.toFloat(),"))
    }

    private fun String.normalizeLines(): String = replace("\r\n", "\n")

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
