package io.legado.app.model

import com.google.gson.JsonObject
import io.legado.app.data.dao.withAudioPlayMode
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPlaybackPreferenceTest {

    @Test
    fun `play mode update preserves unknown read config fields`() {
        val updated = GSON.fromJsonObject<JsonObject>(
            """{"playMode":0,"playSpeed":1.5,"futureAudioOption":"keep"}"""
                .withAudioPlayMode(3)
        ).getOrThrow()

        assertEquals(3, updated.get("playMode").asInt)
        assertEquals(1.5f, updated.get("playSpeed").asFloat)
        assertEquals("keep", updated.get("futureAudioOption").asString)
    }
}
