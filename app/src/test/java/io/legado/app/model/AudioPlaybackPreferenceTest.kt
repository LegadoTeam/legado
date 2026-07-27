package io.legado.app.model

import com.google.gson.JsonObject
import io.legado.app.data.dao.withAudioPlayMode
import io.legado.app.data.dao.withAudioPlaySpeed
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun `play speed update preserves other read config fields`() {
        val updated = GSON.fromJsonObject<JsonObject>(
            """{"playMode":3,"playSpeed":1.0,"futureAudioOption":"keep"}"""
                .withAudioPlaySpeed(2.5f)
        ).getOrThrow()

        assertEquals(3, updated.get("playMode").asInt)
        assertEquals(2.5f, updated.get("playSpeed").asFloat)
        assertEquals("keep", updated.get("futureAudioOption").asString)
    }

    @Test
    fun `play speed is retained before the service starts`() {
        val source = projectFile("src/main/java/io/legado/app/model/AudioPlay.kt").readText()
        val setSpeed = source.substringAfter("fun setSpeed(speed: Float)")
            .substringBefore("fun adjustProgress")
            .replace(Regex("\\s+"), " ")
        val serviceGuard = setSpeed.indexOf("if (AudioPlayService.isRun)")

        assertTrue(setSpeed.indexOf("speed.coerceIn(0.5f, 3.0f)") in 0..<serviceGuard)
        assertTrue(setSpeed.indexOf("AudioPlayService.playSpeed = clampedSpeed") in 0..<serviceGuard)
        assertTrue(setSpeed.indexOf("currentBook.setPlaySpeed(clampedSpeed)") in 0..<serviceGuard)
        assertTrue(setSpeed.contains("else { postEvent(EventBus.AUDIO_SPEED, clampedSpeed) }"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
