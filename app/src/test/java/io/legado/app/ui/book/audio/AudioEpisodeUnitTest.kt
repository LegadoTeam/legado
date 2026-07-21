package io.legado.app.ui.book.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AudioEpisodeUnitTest {

    @Test
    fun audioAndReadAloudKeepSeparateChineseCountUnits() {
        assertEquals("正在朗读（还剩 %d 章）", chineseString("read_aloud_timer_chapter"))
        assertEquals("正在播放（还剩 %d 集）", chineseString("playing_timer_chapter"))
        assertEquals("%d 章", chineseString("sleep_timer_chapters"))
        assertEquals("听完 %d 集", chineseString("audio_stop_chapters"))
        assertEquals("按集数", chineseString("sleep_timer_by_episode"))
        assertEquals("Chapters", defaultString("sleep_timer_by_episode"))
        assertEquals("第 %1\$d 集 · 共 %2\$d 集", chineseString("audio_chapter_progress"))
        assertEquals("Chapter %1\$d / %2\$d", defaultString("audio_chapter_progress"))
    }

    @Test
    fun audioPlayerUsesEpisodeLabelsWhileReadAloudKeepsChapterLabels() {
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/book/audio/AudioPlayActivity.kt"
        ).readText()
        val readAloudDialog = projectFile(
            "src/main/java/io/legado/app/ui/book/read/config/ReadAloudDialog.kt"
        ).readText()
        val dialog = projectFile(
            "src/main/java/io/legado/app/ui/widget/dialog/SleepTimerDialog.kt"
        ).readText()
        val viewModel = projectFile(
            "src/main/java/io/legado/app/ui/book/audio/AudioPlayViewModel.kt"
        ).readText()

        assertTrue(activity.contains("R.string.audio_stop_chapters"))
        assertTrue(activity.contains("R.string.audio_chapter_progress"))
        assertTrue(activity.contains("AudioPlay.durChapterIndex + 1"))
        assertTrue(activity.contains("binding.tvChapterIndex.visible()"))
        assertTrue(activity.contains("binding.tvChapterIndex.gone()"))
        assertEquals(2, Regex("AudioPlay\\.upData\\(book\\)").findAll(viewModel).count())
        assertTrue(
            Regex(
                "SleepTimerDialog\\.newInstance\\(\\s*" +
                    "AudioPlayService\\.timeMinute,\\s*" +
                    "AudioPlayService\\.chapterToStop,\\s*" +
                    "useEpisodes = true,\\s*\\)"
            ).containsMatchIn(activity)
        )
        assertTrue(
            Regex(
                "SleepTimerDialog\\.newInstance\\(\\s*" +
                    "BaseReadAloudService\\.timeMinute,\\s*" +
                    "BaseReadAloudService\\.chapterToStop,\\s*\\)"
            ).containsMatchIn(readAloudDialog)
        )
        assertTrue(
            Regex(
                "if \\(useEpisodes\\)\\s*R\\.string\\.sleep_timer_by_episode\\s*" +
                    "else R\\.string\\.sleep_timer_by_chapter"
            ).containsMatchIn(dialog)
        )
        assertTrue(
            Regex(
                "if \\(useEpisodes\\)\\s*R\\.string\\.audio_stop_chapters\\s*" +
                    "else R\\.string\\.sleep_timer_chapters"
            ).containsMatchIn(dialog)
        )
    }

    private fun chineseString(name: String) = stringValue("values-zh", name)

    private fun defaultString(name: String) = stringValue("values", name)

    private fun stringValue(directory: String, name: String): String {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(projectFile("src/main/res/$directory/strings.xml"))
        return document.getElementsByTagName("string").let { nodes ->
            (0 until nodes.length)
                .map { nodes.item(it) as Element }
                .single { it.getAttribute("name") == name }
                .textContent
        }
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
