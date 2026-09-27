package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AudioSkipPolicyTest {

    @Test
    fun unknownDurationDisablesSkipping() {
        assertNull(resolveAudioSkipWindow(-1L, introSeconds = 30, outroSeconds = 30))
        assertNull(resolveAudioSkipWindow(0L, introSeconds = 30, outroSeconds = 30))
    }

    @Test
    fun shortAudioKeepsTheWholeChapterPlayable() {
        assertNull(resolveAudioSkipWindow(65_000L, introSeconds = 30, outroSeconds = 30))
    }

    @Test
    fun validDurationReturnsSafeIntroAndOutroBoundaries() {
        assertEquals(
            AudioSkipWindow(introEndMs = 30_000L, outroStartMs = 100_000L),
            resolveAudioSkipWindow(120_000L, introSeconds = 30, outroSeconds = 20),
        )
        assertEquals(
            AudioSkipWindow(introEndMs = 30_000L, outroStartMs = 35_001L),
            resolveAudioSkipWindow(65_001L, introSeconds = 30, outroSeconds = 30),
        )
    }

    @Test
    fun serviceDefersIntroAndRejectsStaleChapterCallbacks() {
        val source = projectFile(
            "src/main/java/io/legado/app/service/AudioPlayService.kt"
        ).readText()
        val audioPlay = projectFile(
            "src/main/java/io/legado/app/model/AudioPlay.kt"
        ).readText()
        val play = source.substringAfter("private fun play(")
            .substringBefore("private fun localMediaItem")
        val resume = source.substringAfter("private fun resume()")
            .substringBefore("private fun adjustProgress")
        val upData = audioPlay.substringAfter("fun upData(book: Book, preserveProgress: Boolean)")
            .substringBefore("fun resetData(book: Book)")
        val resetData = audioPlay.substringAfter("fun resetData(book: Book)")
            .substringBefore("fun replaceBook(book: Book)")
        val stop = audioPlay.substringAfter("fun stop()")
            .substringBefore("fun setSpeed")
        val ready = source.substringAfter("Player.STATE_READY -> {")
            .substringBefore("Player.STATE_ENDED -> {")
        val ended = source.substringAfter("Player.STATE_ENDED -> {")
            .substringBefore("private fun handleIsPlayingChanged")
        val intro = source.substringAfter("private fun applyIntroSkipIfNeeded()")
            .substringBefore("private fun tryAutoSkipOutro")
        val progress = source.substringAfter("private fun upPlayProgress()")
            .substringBefore("private fun upMediaSessionPlaybackState")
        val playerError = source.substringAfter("private fun handlePlayerError")
            .substringBefore("private fun setTimer")
        val listener = source.substringAfter("private fun installPlayerListener")
            .substringBefore("private fun handlePlaybackStateChanged")
        val skipTo = audioPlay.substringAfter("fun skipTo(index: Int)")
            .substringBefore("fun prev()")
        val previous = audioPlay.substringAfter("fun prev()")
            .substringBefore("fun next()")
        val next = audioPlay.substringAfter("fun prev()")
            .substringAfter("fun next()")
            .substringBefore("fun setTimer")
        val stopPlay = audioPlay.substringAfter("fun stopPlay()")
            .substringBefore("fun saveRead")

        assertFalse(play.contains("getOpenCredits"))
        assertTrue(play.contains("generation: Long = AudioPlay.currentPlaybackGeneration()"))
        assertTrue(play.contains("val requestUrl = url"))
        assertTrue(play.contains("localMediaItem(requestUrl)"))
        assertTrue(play.contains("AudioPlay.runIfPlaybackCurrent(generation)"))
        assertTrue(play.indexOf("runIfPlaybackCurrent(generation)") < play.indexOf("requestFocus()"))
        assertTrue(play.indexOf("runIfPlaybackCurrent(generation)") < play.indexOf("wakeLock.acquire()"))
        assertTrue(play.contains("resetAudioSkipState(generation)"))
        assertTrue(play.contains("exoPlayer.playWhenReady = false"))
        assertTrue(resume.contains("currentUrl = AudioPlay.durMediaUrl"))
        assertTrue(resume.indexOf("url = currentUrl") < resume.indexOf("play(preservePosition = true, generation = generation)"))
        assertTrue(resume.contains("AudioPlay.runIfPlaybackCurrent(generation)"))
        assertTrue(upData.indexOf("stopPlay()") < upData.indexOf("AudioPlay.book = book"))
        assertTrue(resetData.indexOf("stop()") < resetData.indexOf("AudioPlay.book = book"))
        assertTrue(stop.indexOf("invalidatePlayback()") < stop.indexOf("startService"))
        assertTrue(ready.indexOf("runIfPlaybackCurrent(generation)") < ready.indexOf("applyIntroSkipIfNeeded()"))
        assertTrue(ready.contains("applyIntroSkipIfNeeded()"))
        assertTrue(ready.indexOf("applyIntroSkipIfNeeded()") < ready.indexOf("exoPlayer.play()"))
        assertTrue(ready.contains("if (startProgress) upPlayProgress()"))
        assertTrue(ended.contains("completeCurrentChapter(generation)"))
        assertTrue(intro.contains("if (position > 0) return"))
        assertTrue(intro.contains("if (!exoPlayer.isCurrentMediaItemSeekable) return"))
        assertFalse(intro.contains("AudioPlay.playPositionChanged"))
        assertTrue(progress.contains("val generation = playbackGeneration"))
        assertTrue(progress.contains("chapterCompleted = tryAutoSkipOutro(durP, generation)"))
        assertTrue(source.contains("chapterCompletionHandled || !isCurrentPlayback(generation)"))
        assertTrue(source.contains("chapterStopTimer.onChapterCompleted()"))
        assertTrue(progress.contains("AudioPlay.runIfPlaybackCurrent(generation)"))
        assertTrue(progress.contains("if (!handled || pause || chapterCompleted) break"))
        assertTrue(listener.contains("this === playerListener"))
        assertTrue(listener.contains("handlePlayerError(error, generation)"))
        assertTrue(playerError.contains("AudioPlay.runIfPlaybackCurrent(generation)"))
        assertTrue(skipTo.contains("stopPlayAndGetGeneration()"))
        assertTrue(skipTo.contains("loadPlayUrlWhenCurrent(generation)"))
        assertTrue(previous.contains("stopPlayAndGetGeneration()"))
        assertTrue(previous.contains("loadPlayUrlWhenCurrent(generation)"))
        assertTrue(next.contains("stopPlayAndGetGeneration()"))
        assertTrue(next.contains("generation != playbackGeneration.get()"))
        assertTrue(stopPlay.contains("stopPlayAndGetGeneration()"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
