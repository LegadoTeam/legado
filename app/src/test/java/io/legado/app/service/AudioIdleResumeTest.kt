package io.legado.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AudioIdleResumeTest {

    @Test
    fun idleResumeUsesCurrentAudioProgress() {
        val source = projectFile(
            "src/main/java/io/legado/app/service/AudioPlayService.kt"
        ).readText()
        val resumeBody = source.substringAfter("private fun resume()")
            .substringBefore("private fun adjustProgress")

        assertTrue(resumeBody.contains("currentUrl = AudioPlay.durMediaUrl"))
        assertTrue(resumeBody.contains("currentPosition = AudioPlay.book?.durChapterPos ?: position"))
        assertTrue(resumeBody.contains("url != currentUrl"))
        assertTrue(resumeBody.contains("position = currentPosition"))
        assertTrue(resumeBody.contains("play(preservePosition = true, generation = generation)"))
        assertFalse(resumeBody.contains("position = 0"))
    }

    @Test
    fun positionChangesStayInSyncWithAudioPlay() {
        val source = projectFile(
            "src/main/java/io/legado/app/service/AudioPlayService.kt"
        ).readText()

        val pauseBody = source.substringAfter("private fun pause(")
            .substringBefore("private fun resume()")
        assertTrue(pauseBody.contains("AudioPlay.playPositionChanged(position)"))

        val seekBody = source.substringAfter("override fun onSeekTo(pos: Long)")
            .substringBefore("override fun onMediaButtonEvent")
        assertTrue(seekBody.contains("AudioPlay.playPositionChanged(position)"))
    }

    @Test
    fun jsonPlaybackDoesNotDiscardAResumedPosition() {
        val source = projectFile(
            "src/main/java/io/legado/app/service/AudioPlayService.kt"
        ).readText()
        val playBody = source.substringAfter("private fun play(")
            .substringBefore("private fun localMediaItem")

        assertTrue(
            Regex(
                "val startPosition = if \\(requestUrl\\.isJsonArray\\(\\) && !preservePosition\\) " +
                        "\\{\\s*0\\s*} else \\{\\s*requestPosition\\s*}"
            ).containsMatchIn(playBody)
        )
        assertTrue(playBody.contains("position = startPosition"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
