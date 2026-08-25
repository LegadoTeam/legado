package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TtsPageFollowTest {

    @Test
    fun `speech page catches up without moving backward`() {
        assertEquals(0, pendingSpeechPageMoves(0, 0))
        assertEquals(1, pendingSpeechPageMoves(0, 1))
        assertEquals(2, pendingSpeechPageMoves(0, 2))
        assertEquals(0, pendingSpeechPageMoves(2, 1))
        assertEquals(0, pendingSpeechPageMoves(0, -1))
    }

    @Test
    fun `app log records only the first tts start and range callbacks`() {
        val service = listOf(
            File("src/main/java/io/legado/app/service/TTSReadAloudService.kt"),
            File("app/src/main/java/io/legado/app/service/TTSReadAloudService.kt")
        ).first(File::isFile).readText()

        assertTrue(service.contains("AppConfig.recordLog && !startCallbackLogged"))
        assertTrue(service.contains("AppConfig.recordLog && !rangeCallbackLogged"))
        assertTrue(service.contains("AppLog.putDebug(\"\$TAG \$msg\")"))
    }

    @Test
    fun `stale tts callbacks cannot mutate a newer playback session`() {
        val service = listOf(
            File("src/main/java/io/legado/app/service/TTSReadAloudService.kt"),
            File("app/src/main/java/io/legado/app/service/TTSReadAloudService.kt")
        ).first(File::isFile).readText()

        assertTrue(service.contains("private val playbackSessionId = AtomicLong()"))
        assertTrue(service.contains("speakCurrent("))
        assertTrue(service.contains("TextToSpeech.QUEUE_FLUSH"))
        assertTrue(service.contains("utteranceId(sessionId, index)"))
        assertTrue(service.contains("callbackHandler.post"))
        assertTrue(service.contains("if (sessionId != playbackSessionId.get()) return"))
        assertTrue(service.contains("if (sessionId == playbackSessionId.get()) block()"))
        assertTrue(
            service.substringAfter("private fun dispatchCurrentCallback")
                .contains("synchronized(this)")
        )
        assertTrue(service.contains("private fun speakCurrent("))
        assertTrue(service.contains("synchronized(this)"))
    }
}
