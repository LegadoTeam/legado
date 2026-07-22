package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioPlayUrlPreloadStoreTest {

    private val firstKey = AudioPlayUrlKey("book", "source", 1)
    private val secondKey = AudioPlayUrlKey("book", "source", 2)

    @Test
    fun `completed preload is consumed only by the matching chapter`() {
        val store = AudioPlayUrlPreloadStore()
        val generation = store.begin(firstKey)!!
        store.complete(firstKey, generation, "https://example.com/1.mp3", "line one")

        assertNull(store.consume(secondKey))
        assertEquals(
            PreloadedAudioPlayUrl("https://example.com/1.mp3", "line one"),
            store.consume(firstKey),
        )
        assertNull(store.consume(firstKey))
    }

    @Test
    fun `reset rejects a late result from an old book or source`() {
        val store = AudioPlayUrlPreloadStore()
        val generation = store.begin(firstKey)!!
        store.reset()
        store.complete(firstKey, generation, "https://example.com/stale.mp3")

        assertNull(store.consume(firstKey))
    }

    @Test
    fun `same chapter is not requested twice while loading`() {
        val store = AudioPlayUrlPreloadStore()
        val generation = store.begin(firstKey)

        assertNull(store.begin(firstKey))
        store.finish(firstKey)
        assertEquals(generation, store.begin(firstKey))
    }
}
