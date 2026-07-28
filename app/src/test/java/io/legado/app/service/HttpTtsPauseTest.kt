package io.legado.app.service

import io.legado.app.data.entities.HttpTTS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HttpTtsPauseTest {

    @Test
    fun `pause duration is constrained to a safe range`() {
        assertEquals(0, normalizeHttpTtsPauseDuration(-1))
        assertEquals(500, normalizeHttpTtsPauseDuration(500))
        assertEquals(MAX_HTTP_TTS_PAUSE_MS, normalizeHttpTtsPauseDuration(Int.MAX_VALUE))
    }

    @Test
    fun `pause is inserted only between paragraphs`() {
        assertFalse(shouldInsertHttpTtsPause(0, 2, 0))
        assertTrue(shouldInsertHttpTtsPause(0, 2, 300))
        assertTrue(shouldInsertHttpTtsPause(1, 2, 300))
        assertFalse(shouldInsertHttpTtsPause(2, 2, 300))
    }

    @Test
    fun `player queue keeps room for a paragraph and its pause`() {
        assertTrue(hasHttpTtsQueueCapacity(10, 2))
        assertFalse(hasHttpTtsQueueCapacity(11, 2))
        assertFalse(hasHttpTtsQueueCapacity(MAX_HTTP_TTS_PLAYER_QUEUE_SIZE, 1))
    }

    @Test
    fun `media ids retain their playback session`() {
        assertEquals(7L, httpTtsMediaSessionId("http-tts:7:3"))
        assertEquals(7L, httpTtsMediaSessionId("http-tts-pause:7:500"))
        assertNull(httpTtsMediaSessionId("7:3"))
        assertNull(httpTtsMediaSessionId("legacy-media-id"))
    }

    @Test
    fun `service keeps playback generation and queue backpressure wired`() {
        val source = listOf(
            File("src/main/java/io/legado/app/service/HttpReadAloudService.kt"),
            File("app/src/main/java/io/legado/app/service/HttpReadAloudService.kt")
        ).first { it.isFile }.readText().replace("\r\n", "\n")
        val playStop = source.substringAfter("override fun playStop()")
            .substringBefore("private fun updateNextPos")
        val speechRate = source.substringAfter("override fun upSpeechRate")
            .substringBefore("override fun onPlaybackStateChanged")
        val transition = source.substringAfter("override fun onMediaItemTransition")
            .substringBefore("override fun onPlayerError")
        val cancelDownload = source.substringAfter("private fun cancelDownloadTask()")
            .substringBefore("private fun invalidatePlaybackSession")
        val dataSource = source.substringAfter("private fun createDataSourceFactory(")
            .substringBefore("private fun createDownloader")
        val speakStream = source.substringAfter("private suspend fun getSpeakStream(")
            .substringBefore("private fun md5SpeakFileName")
        val downloaderLoop = source.substringAfter("for (downloader in downloaderChannel) {")
            .substringBefore("activeDownloader.compareAndSet")
        val createSpeakFile = source.substringAfter("private suspend fun createSpeakFile(")
            .substringBefore("private fun getOrCreatePauseFile")
        val queueWait = source.substringAfter("private suspend fun awaitQueueSlots")
            .substringBefore("private fun startPlaybackSession")
        val baseSource = listOf(
            File("src/main/java/io/legado/app/service/BaseReadAloudService.kt"),
            File("app/src/main/java/io/legado/app/service/BaseReadAloudService.kt")
        ).first { it.isFile }.readText().replace("\r\n", "\n")
        val newReadAloud = baseSource.substringAfter("private fun newReadAloud(")
            .substringBefore("@SuppressLint(\"WakelockTimeout\")")
        val ttsSource = listOf(
            File("src/main/java/io/legado/app/service/TTSReadAloudService.kt"),
            File("app/src/main/java/io/legado/app/service/TTSReadAloudService.kt")
        ).first { it.isFile }.readText().replace("\r\n", "\n")
        val ttsPlayStop = ttsSource.substringAfter("override fun playStop()")
            .substringBefore("/**")

        assertTrue(source.contains("private val playbackSessionId = AtomicLong()"))
        assertTrue(source.contains("hasHttpTtsQueueCapacity(exoPlayer.mediaItemCount, requiredSlots)"))
        assertTrue(source.contains("if (!isSessionActive(sessionId)) return@withContext"))
        assertTrue(source.contains("enqueueMediaItems(sessionId, listOfNotNull(mediaItem, pauseItem))"))
        assertTrue(source.contains("downloaderChannel.close()"))
        assertTrue(source.contains("activeDownloader.compareAndSet(downloader, null)"))
        assertTrue(
            downloaderLoop.indexOf("runInterruptible") <
                downloaderLoop.indexOf("downloader.download(null)")
        )
        assertFalse(Regex("""launch\(Main\)\s*\{\s*exoPlayer\.addMedia""").containsMatchIn(source))
        assertTrue(newReadAloud.indexOf("playStop()") < newReadAloud.indexOf("execute("))
        assertTrue(playStop.contains("invalidatePlaybackSession()"))
        assertTrue(
            playStop.indexOf("invalidatePlaybackSession()") <
                playStop.indexOf("cancelDownloadTask()")
        )
        assertTrue(cancelDownload.contains("activeDownloader.getAndSet(null)?.cancel()"))
        assertTrue(dataSource.contains("getSpeakStream(httpTts, speakText, sessionId)"))
        assertTrue(dataSource.contains("isSessionActive(sessionId)"))
        assertTrue(speakStream.contains("ensureSessionActive(sessionId)"))
        assertTrue(createSpeakFile.contains("runInterruptible"))
        assertTrue(createSpeakFile.contains("file.delete()"))
        assertTrue(queueWait.contains("if (pause)"))
        assertTrue(queueWait.contains("delay(1_000L)"))
        assertTrue(ttsPlayStop.contains("speakJob?.cancel()"))
        assertTrue(speechRate.contains("playStop()"))
        assertTrue(transition.contains("isCurrentSessionMediaItem(mediaItem)"))
        assertTrue(transition.contains("trimPlayedMediaItems()"))
        assertTrue(transition.contains("isPauseMediaItem(mediaItem)"))
    }

    @Test
    fun `silent wav has a valid pcm header and expected duration`() {
        val bytes = generateSilentWavBytes(250)
        assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals("data", bytes.copyOfRange(36, 40).toString(Charsets.US_ASCII))
        val dataSize = ByteBuffer.wrap(bytes, 40, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
        assertEquals(24_000 * 2 / 4, dataSize)
        assertEquals(44 + dataSize, bytes.size)
    }

    @Test
    fun `http tts json keeps pause duration compatible with older exports`() {
        val oldConfig = HttpTTS.fromJson("""{"name":"old","url":"https://example.com"}""")
            .getOrThrow()
        val newConfig = HttpTTS.fromJson(
            """{"name":"new","url":"https://example.com","pauseDuration":750}"""
        ).getOrThrow()
        val oversized = HttpTTS.fromJson(
            """{"name":"large","url":"https://example.com","pauseDuration":999999}"""
        ).getOrThrow()

        assertEquals(0, oldConfig.pauseDuration)
        assertEquals(750, newConfig.pauseDuration)
        assertEquals(MAX_HTTP_TTS_PAUSE_MS, oversized.pauseDuration)
        assertFalse(newConfig.equal(newConfig.copy(pauseDuration = 0)))
    }
}
