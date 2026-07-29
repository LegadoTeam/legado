package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsPageFollowTest {

    @Test
    fun `speech page catches up without moving backward`() {
        assertEquals(0, pendingSpeechPageMoves(0, 0))
        assertEquals(1, pendingSpeechPageMoves(0, 1))
        assertEquals(2, pendingSpeechPageMoves(0, 2))
        assertEquals(0, pendingSpeechPageMoves(2, 1))
        assertEquals(0, pendingSpeechPageMoves(0, -1))
    }
}
