package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChapterStopTimerTest {

    @Test
    fun countIsBoundedAndCompletesAtZero() {
        val timer = ChapterStopTimer(Int.MAX_VALUE)
        assertEquals(MAX_CHAPTER_STOP_COUNT, timer.remaining)

        repeat(MAX_CHAPTER_STOP_COUNT - 1) {
            val result = timer.onChapterCompleted()
            assertFalse(result?.shouldStop ?: true)
        }

        val finalResult = timer.onChapterCompleted()
        assertTrue(finalResult?.shouldStop == true)
        assertEquals(0, finalResult?.remaining)
        assertNull(timer.onChapterCompleted())
    }

    @Test
    fun nonPositiveCountDisablesTimer() {
        val timer = ChapterStopTimer(-1)
        assertEquals(0, timer.remaining)
        assertNull(timer.onChapterCompleted())
    }

    @Test
    fun onlyNaturalReadAloudTransitionsConsumeChapterCount() {
        val root = listOf(File("src/main/java"), File("app/src/main/java"))
            .first { it.isDirectory }
        val base = File(root, "io/legado/app/service/BaseReadAloudService.kt").readText()
        val http = File(root, "io/legado/app/service/HttpReadAloudService.kt").readText()
        val tts = File(root, "io/legado/app/service/TTSReadAloudService.kt").readText()

        assertTrue(base.contains("IntentAction.next -> nextChapter()"))
        assertTrue(base.contains("open fun nextChapter(auto: Boolean = false)"))
        assertTrue(http.contains("nextChapter(auto = true)"))
        assertEquals(2, Regex("nextChapter\\(auto = true\\)").findAll(tts).count())
    }
}
