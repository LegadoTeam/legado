package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadAloudSentenceStartTest {

    @Test
    fun `only visible-position reading rewinds to a sentence`() {
        assertTrue(shouldRewindReadAloudToSentenceStart(true, false))
        assertFalse(shouldRewindReadAloudToSentenceStart(false, false))
        assertFalse(shouldRewindReadAloudToSentenceStart(true, true))
    }

    @Test
    fun `sentence search uses the nearest terminator and falls back to paragraph start`() {
        val text = "第一句。第二句还很长！第三句"

        assertEquals(text.indexOf("第二句"), findReadAloudSentenceStart(text, text.indexOf("很长")))
        assertEquals(text.indexOf("第三句"), findReadAloudSentenceStart(text, text.indexOf("三句") + 1))
        assertEquals(0, findReadAloudSentenceStart(text, text.indexOf('。')))
        assertEquals(0, findReadAloudSentenceStart("整段没有句末标点", Int.MAX_VALUE))
    }

    @Test
    fun `sentence search keeps opening quotes and ignores decimal dots`() {
        val quoted = "第一句。”  “第二句仍在继续"
        val decimal = "版本 3.14 仍在同一句。下一句"
        val shortSentences = "Hello. Go. Next sentence."

        assertEquals(
            quoted.indexOf('“'),
            findReadAloudSentenceStart(quoted, quoted.indexOf("仍在"))
        )
        assertEquals(0, findReadAloudSentenceStart(decimal, decimal.indexOf("仍在")))
        assertEquals(
            decimal.indexOf("下一句"),
            findReadAloudSentenceStart(decimal, decimal.length)
        )
        assertEquals(
            shortSentences.indexOf("Next"),
            findReadAloudSentenceStart(shortSentences, shortSentences.length)
        )
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
        val rewind = service.substringAfter("if (shouldRewindReadAloudToSentenceStart(")
            .substringBefore("readAloudChapterStart = readAloudNumber")
        val visibleReadCalls = Regex("ReadBook\\.readAloud").findAll(onClick).count()

        assertTrue(visibleReadCalls > 0)
        assertEquals(
            visibleReadCalls,
            Regex("rewindToSentenceStart = AppConfig.readAloudStartAtSentence").findAll(onClick).count()
        )
        assertTrue(onClick.contains("startPos = line.pagePosition"))
        assertTrue(readBook.contains("rewindToSentenceStart: Boolean = false"))
        assertTrue(readBook.contains("rewindToSentenceStart = rewindToSentenceStart"))
        assertTrue(readAloud.contains("rewindToSentenceStart: Boolean = false"))
        assertTrue(readAloud.contains("intent.putExtra(\"rewindToSentenceStart\", rewindToSentenceStart)"))
        assertTrue(rewind.contains("findReadAloudSentenceStart("))
        assertTrue(rewind.contains("readAloudNumber = paragraph.chapterPosition + sentenceStart"))
        assertTrue(rewind.contains("getParagraphs(readAloudByPage)[nowSpeak].chapterPosition"))
        val cursorIndex = service.indexOf("readAloudChapterStart = readAloudNumber")
        val toLastIndex = service.indexOf("if (toLast)")
        assertTrue(cursorIndex >= 0 && toLastIndex in 0 until cursorIndex)
        assertTrue(service.contains("pageIndex = textChapter.getPageIndexByCharIndex(readAloudNumber)"))
        assertTrue(select.contains("ReadBook.readAloud(startPos = startPos, rewindToSentenceStart = false)"))
        assertFalse(select.contains("rewindToSentenceStart = true"))
        assertTrue(readFromHere.contains("ReadBook.readAloud()"))
        assertFalse(readFromHere.contains("rewindToSentenceStart = true"))
    }

    private fun projectFile(path: String): String {
        return sequenceOf(File(path), File("app/$path"))
            .first { it.isFile }
            .readText()
    }
}
