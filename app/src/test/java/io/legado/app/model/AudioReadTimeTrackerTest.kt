package io.legado.app.model

import io.legado.app.data.entities.ReadRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AudioReadTimeTrackerTest {

    @Test
    fun `duplicate starts and stops count each playing interval once`() {
        val tracker = AudioReadTimeTracker()
        tracker.setRecord(ReadRecord(bookName = "book", readTime = 100))

        tracker.start(1_000)
        tracker.start(1_500)
        assertEquals(1_100L, tracker.stop(2_000, 10_000)?.readTime)
        assertNull(tracker.stop(2_500, 11_000))

        tracker.start(3_000)
        assertEquals(1_600L, tracker.stop(3_500, 12_000)?.readTime)
    }

    @Test
    fun `an active interval remains assigned to its original book`() {
        val tracker = AudioReadTimeTracker()
        tracker.setRecord(ReadRecord(bookName = "first"))
        tracker.start(100)
        tracker.setRecord(ReadRecord(bookName = "second"))

        assertEquals("first", tracker.stop(200, 1_000)?.bookName)
        tracker.start(300)
        assertEquals("second", tracker.stop(450, 2_000)?.bookName)
    }

    @Test
    fun `book info can update the author of an active interval`() {
        val tracker = AudioReadTimeTracker()
        tracker.setRecord(ReadRecord(bookName = "book"))
        tracker.start(100)

        tracker.updateAuthor("author")

        assertEquals("author", tracker.stop(200, 1_000)?.author)
    }

    @Test
    fun `service records only actual playing state`() {
        val source = projectFile(
            "src/main/java/io/legado/app/service/AudioPlayService.kt"
        ).readText()
        val callback = source.substringAfter("private fun handleIsPlayingChanged(")
            .substringBefore("override fun onSharedPreferenceChanged")
            .replace(Regex("\\s+"), " ")

        assertTrue(
            callback.contains(
                "if (isPlaying) { AudioPlay.markReadTimeStart() } else { AudioPlay.upReadTime() }"
            )
        )

        val preferenceCallback = source.substringAfter("override fun onSharedPreferenceChanged(")
            .substringBefore("private fun upMediaMetadata")
            .replace(Regex("\\s+"), " ")
        assertTrue(preferenceCallback.contains("key != PreferKey.enableReadRecord"))
        assertTrue(
            preferenceCallback.contains(
                "if (AppConfig.enableReadRecord && exoPlayer.isPlaying)"
            )
        )

        val model = projectFile("src/main/java/io/legado/app/model/AudioPlay.kt")
            .readText()
            .replace(Regex("\\s+"), " ")
        assertTrue(model.contains("@Synchronized fun upReadTime()"))
        assertTrue(model.contains("readTimeWrite = executor.submit"))

        val viewModel = projectFile(
            "src/main/java/io/legado/app/ui/book/audio/AudioPlayViewModel.kt"
        ).readText()
        assertTrue(viewModel.contains("AudioPlay.replaceBook(book)"))
    }

    @Test
    fun `generation invalidation settles old playback before stopping the player`() {
        val model = projectFile("src/main/java/io/legado/app/model/AudioPlay.kt").readText()
        val stopRequest = model.substringAfter("private fun stopPlayAndGetGeneration()")
            .substringBefore("fun stopPlay()")
        val stopAction = stopRequest.indexOf("IntentAction.stopPlay")
        assertTrue(stopRequest.indexOf("invalidatePlayback()") in 0..<stopAction)

        val service = projectFile(
            "src/main/java/io/legado/app/service/AudioPlayService.kt"
        ).readText()
        val stopBranch = service.substringAfter("IntentAction.stopPlay -> {")
            .substringBefore("IntentAction.pause ->")
        val clearPlaying = stopBranch.indexOf("isPlaying = false")
        val settleReadTime = stopBranch.indexOf("AudioPlay.upReadTime()")
        val playerStop = stopBranch.indexOf("exoPlayer.stop()")
        assertTrue(clearPlaying in 0..<settleReadTime)
        assertTrue(settleReadTime in 0..<playerStop)
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
