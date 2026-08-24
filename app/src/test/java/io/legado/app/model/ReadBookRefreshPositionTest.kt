package io.legado.app.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadBookRefreshPositionTest {

    @Test
    fun `reader refresh preserves position before discarding layout`() {
        val refresh = source("app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
            .substringAfter("private fun refreshDurChapter()")
            .substringBefore("private fun refreshAfterChapters()")

        assertOrder(
            refresh,
            "ReadBook.preserveCurrentPositionForRefresh()",
            "ReadBook.curTextChapter = null",
            "viewModel.refreshContentDur(it)"
        )
    }

    @Test
    fun `chapter list refresh carries the same anchor into the next layout`() {
        val readBook = source("app/src/main/java/io/legado/app/model/ReadBook.kt")
        val chapterUpdate = readBook.substringAfter("fun onChapterListUpdated(")
            .substringBefore("private fun clearExpiredChapterLoadingJob")
        val resetData = readBook.substringAfter("fun resetData(book: Book)")
            .substringBefore("fun loadHighlights")
        val anchorCapture = readBook.substringAfter("private fun currentPositionAnchor()")
            .substringBefore("private data class PendingHighlightJump")

        assertOrder(
            chapterUpdate,
            "currentPositionAnchor()",
            "clearTextChapter()",
            "pendingHighlightAnchor = positionAnchor"
        )
        assertTrue(anchorCapture.contains("chapterText(textChapter).drop(titleLength)"))
        assertTrue(anchorCapture.contains("bodyText.drop(bodyPosition).take(REFRESH_POSITION_ANCHOR_LENGTH)"))
        assertTrue(anchorCapture.contains("waitForLayout = true"))
        assertOrder(
            resetData,
            "val positionAnchor = pendingHighlightAnchor",
            "clearTextChapter()",
            "pendingHighlightAnchor = positionAnchor?.takeIf"
        )
        assertTrue(readBook.contains("return pendingHighlightAnchor?.waitForLayout != true"))
    }

    @Test
    fun `completed layout resolves refresh anchor without waiting for callback flag`() {
        val readBook = source("app/src/main/java/io/legado/app/model/ReadBook.kt")
        val loadCurrentChapter = readBook.substringAfter("suspend fun contentLoadFinishAwait(")
            .substringBefore("fun pageAnim()")
            .substringAfter("0 -> {")
            .substringBefore("-1 -> {")
        val anchorResolver = readBook.substringAfter("private fun resolvePendingHighlightAnchor(")
            .substringBefore("private fun currentPositionAnchor()")

        assertOrder(
            loadCurrentChapter,
            "for (page in textChapter.layoutChannel)",
            "resolvePendingHighlightAnchor(book, textChapter)"
        )
        assertFalse(anchorResolver.contains("textChapter.isCompleted"))
    }

    @Test
    fun `toc refresh carries a scroll version while explicit jumps keep reset`() {
        val readBook = source("app/src/main/java/io/legado/app/model/ReadBook.kt")
        val chapterUpdate = readBook.substringAfter("fun onChapterListUpdated(")
            .substringBefore("private fun shouldApplyReadPositionReset")
        assertTrue(chapterUpdate.contains("readPositionVersion = callBack?.readPositionVersion()"))
        assertTrue(readBook.contains("readPositionVersion = readPositionVersion"))
        assertTrue(readBook.contains("resetPageOffset = resetPageOffset"))

        val openChapter = readBook.substringAfter("fun openChapter(")
            .substringBefore("private fun curPageChanged")
        assertTrue(openChapter.contains("loadContent(resetPageOffset = true)"))
        assertFalse(openChapter.contains("readPositionVersion ="))

        val setProgress = readBook.substringAfter("fun setProgress(")
            .substringBefore("//暂时保存跳转前进度")
        assertTrue(setProgress.contains("loadContent(resetPageOffset = true)"))
        assertFalse(setProgress.contains("readPositionVersion ="))

        val activity = source("app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
        val upContent = activity.substringAfter("override fun upContent(")
            .substringBefore("override fun readPositionVersion")
        assertTrue(upContent.contains("isReadPositionVersionCurrent(readPositionVersion)"))
    }

    private fun assertOrder(source: String, vararg expected: String) {
        var position = -1
        expected.forEach { text ->
            val next = source.indexOf(text)
            assertTrue("Missing or out of order: $text", next > position)
            position = next
        }
    }

    private fun source(path: String): String {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, path).readText().replace("\r\n", "\n")
    }
}
