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
    fun notificationTimerIncrementKeepsActiveModeAndUsesIdlePreference() {
        assertEquals(
            SleepTimerIncrement(minute = 30),
            nextSleepTimerIncrement(timeMinute = 20, chapterToStop = 0, preferChapter = true)
        )
        assertEquals(
            SleepTimerIncrement(chapter = 3),
            nextSleepTimerIncrement(timeMinute = 0, chapterToStop = 2, preferChapter = false)
        )
        assertEquals(
            SleepTimerIncrement(chapter = 1),
            nextSleepTimerIncrement(timeMinute = 0, chapterToStop = 0, preferChapter = true)
        )
        assertEquals(
            SleepTimerIncrement(minute = 10),
            nextSleepTimerIncrement(timeMinute = 0, chapterToStop = 0, preferChapter = false)
        )
        assertEquals(
            SleepTimerIncrement(),
            nextSleepTimerIncrement(timeMinute = 180, chapterToStop = 0, preferChapter = false)
        )
        assertEquals(
            SleepTimerIncrement(chapter = MAX_CHAPTER_STOP_COUNT),
            nextSleepTimerIncrement(
                timeMinute = 0,
                chapterToStop = MAX_CHAPTER_STOP_COUNT,
                preferChapter = false,
            )
        )
    }

    @Test
    fun notificationTimerButtonsAndDialogUseSharedPreference() {
        val root = listOf(File("src/main/java"), File("app/src/main/java"))
            .first { it.isDirectory }
        val audio = File(root, "io/legado/app/service/AudioPlayService.kt").readText()
        val readAloud = File(root, "io/legado/app/service/BaseReadAloudService.kt").readText()
        val dialog = File(root, "io/legado/app/ui/widget/dialog/SleepTimerDialog.kt").readText()
        val incrementCall = "timeMinute, chapterToStop, AppConfig.sleepTimerPreferChapter"

        assertTrue(audio.contains(incrementCall))
        assertTrue(readAloud.contains(incrementCall))
        assertTrue(dialog.contains("if (chapter > 0) AppConfig.sleepTimerPreferChapter = true"))
        assertTrue(dialog.contains("if (minute > 0) AppConfig.sleepTimerPreferChapter = false"))
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
