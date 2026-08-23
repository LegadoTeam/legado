package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadAloudParagraphStartTest {

    @Test
    fun `only visible-position reading rewinds to a real paragraph`() {
        assertTrue(shouldRewindReadAloudToParagraphStart(true, false, false))
        assertFalse(shouldRewindReadAloudToParagraphStart(false, false, false))
        assertFalse(shouldRewindReadAloudToParagraphStart(true, true, false))
        assertFalse(shouldRewindReadAloudToParagraphStart(true, false, true))
    }

    @Test
    fun `visible-position intent reaches the shared read aloud service`() {
        val activity = projectFile("src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
        val readBook = projectFile("src/main/java/io/legado/app/model/ReadBook.kt")
        val readAloud = projectFile("src/main/java/io/legado/app/model/ReadAloud.kt")
        val service = projectFile("src/main/java/io/legado/app/service/BaseReadAloudService.kt")
        val select = projectFile("src/main/java/io/legado/app/ui/book/read/page/ReadView.kt")
        val onClick = activity.substringAfter("override fun onClickReadAloud()")
            .substringBefore("override fun showHelp()")
        val readFromHere = activity.substringAfter("llReadFromHere.setOnClickListener")
            .substringBefore("}")
        val rewind = service.substringAfter("if (shouldRewindReadAloudToParagraphStart(")
            .substringBefore("readAloudChapterStart = readAloudNumber")
        val visibleReadCalls = Regex("ReadBook\\.readAloud").findAll(onClick).count()

        assertTrue(visibleReadCalls > 0)
        assertEquals(
            visibleReadCalls,
            Regex("rewindToParagraphStart = true").findAll(onClick).count()
        )
        assertTrue(onClick.contains("startPos = line.pagePosition"))
        assertTrue(readBook.contains("rewindToParagraphStart: Boolean = false"))
        assertTrue(readBook.contains("rewindToParagraphStart = rewindToParagraphStart"))
        assertTrue(readAloud.contains("rewindToParagraphStart: Boolean = false"))
        assertTrue(readAloud.contains("intent.putExtra(\"rewindToParagraphStart\", rewindToParagraphStart)"))
        assertTrue(rewind.contains("readAloudNumber = textChapter.paragraphs[nowSpeak].chapterPosition"))
        assertTrue(rewind.contains("pos = 0"))
        assertTrue(rewind.contains("else if (!readAloudByPage && startPos == 0 && !toLast)"))
        assertTrue(rewind.contains("pos = page.chapterPosition"))
        val cursorIndex = service.indexOf("readAloudChapterStart = readAloudNumber")
        val toLastIndex = service.indexOf("if (toLast)", cursorIndex)
        assertTrue(cursorIndex >= 0 && toLastIndex > cursorIndex)
        assertTrue(select.contains("ReadBook.readAloud(startPos = startPos)"))
        assertFalse(select.contains("rewindToParagraphStart = true"))
        assertTrue(readFromHere.contains("ReadBook.readAloud()"))
        assertFalse(readFromHere.contains("rewindToParagraphStart = true"))
    }

    private fun projectFile(path: String): String {
        return sequenceOf(File(path), File("app/$path"))
            .first { it.isFile }
            .readText()
    }
}
