package io.legado.app.service

import io.legado.app.data.entities.HttpTTS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
