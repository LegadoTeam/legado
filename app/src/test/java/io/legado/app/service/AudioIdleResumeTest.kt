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
        val idleBranch = Regex(
            "if \\(exoPlayer\\.playbackState == Player\\.STATE_IDLE\\) \\{(.*?)\\n\\s*}",
            RegexOption.DOT_MATCHES_ALL,
        ).find(source)?.groupValues?.get(1) ?: error("Missing STATE_IDLE resume branch")

        assertTrue(
            idleBranch.contains(
                "position = AudioPlay.book?.let { AudioPlay.durChapterPos } ?: position"
            )
        )
        assertTrue(idleBranch.contains("play(preservePosition = true)"))
        assertFalse(idleBranch.contains("position = 0"))
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
        val jsonBranch = source.substringAfter("if (url.isJsonArray())")
            .substringBefore("} else {")

        assertTrue(jsonBranch.contains("if (!preservePosition)"))
        assertTrue(jsonBranch.contains("position = 0"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
