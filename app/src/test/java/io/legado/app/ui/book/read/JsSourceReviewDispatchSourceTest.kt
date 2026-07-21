package io.legado.app.ui.book.read

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JsSourceReviewDispatchSourceTest {

    @Test
    fun `JavaScript review dispatch runs before declarative rule gates`() {
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"
        ).readText().normalizeLines()
        val clickBlock = activity.substringAfter("override fun onReviewClick(")
            .substringBefore("private fun loadReviewSummaryIfNeeded(")
        val summaryBlock = activity.substringAfter("private fun loadReviewSummaryIfNeeded(")
            .substringBefore("private fun loadJsReviewSummaryIfNeeded(")
        val jsSummaryBlock = activity.substringAfter("private fun loadJsReviewSummaryIfNeeded(")
            .substringBefore("private fun clearReviewSummaryProviders(")

        assertTrue(clickBlock.indexOf("if (source.isJsSource())") < clickBlock.indexOf("source.ruleReview"))
        assertTrue(summaryBlock.indexOf("if (source.isJsSource())") < summaryBlock.indexOf("source.ruleReview"))
        assertTrue(clickBlock.contains("ruleHash = source.mainJs.hashCode()"))
        assertTrue(jsSummaryBlock.contains("val sourceHash = source.mainJs.hashCode()"))
        assertTrue(jsSummaryBlock.contains("buildReviewSummaryKey(book, source, sourceHash, chapterIndex)"))
        assertTrue(jsSummaryBlock.contains("reviewSummaryAppliedKey = key"))
    }

    @Test
    fun `JavaScript detail uses captured source chapter and pagination state`() {
        val dialog = projectFile(
            "src/main/java/io/legado/app/ui/book/read/ReviewDetailDialog.kt"
        ).readText().normalizeLines()
        val loadBlock = dialog.substringAfter("private fun loadDetailPage(")
            .substringBefore("private fun buildDetailItemKey(")

        assertTrue(loadBlock.contains("if (source.mainJs.hashCode() != ruleHash)"))
        assertTrue(loadBlock.contains("if (book.bookUrl != bookUrl)"))
        assertTrue(loadBlock.contains("getChapter(book.bookUrl, chapterIndex)"))
        assertTrue(loadBlock.contains("paragraphData = paragraphData"))
        assertTrue(loadBlock.contains("hasNextPageRule = true"))
        val jsBlock = loadBlock.substringAfter("if (source.isJsSource())")
            .substringBefore("val rule = source.ruleReview")
        assertTrue(!jsBlock.contains("ReadBook.durChapterIndex"))
        assertTrue(
            loadBlock.contains(
                "if (nextUrlFromRule.isNullOrBlank()) {\n                    hasMore = false"
            )
        )
    }

    private fun String.normalizeLines(): String = replace("\r\n", "\n")

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
