package io.legado.app.help.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentSaveFenceTest {

    @Test
    fun `authoritative replacement rejects an older network write`() {
        val fence = ContentSaveFence()
        val key = ContentSaveKey("book", 3)
        val requestVersion = fence.state(key).version
        var content = ""

        fence.replace(key, "replacement.nb") { content = "replacement" }

        assertFalse(
            fence.writeIfCurrent(key, requestVersion, "stale.nb") {
                content = "stale network content"
            }
        )
        assertEquals("replacement", content)
        assertEquals("replacement.nb", fence.state(key).fileName)
    }

    @Test
    fun `initial network write does not retain fence state`() {
        val fence = ContentSaveFence()
        val key = ContentSaveKey("book", 3)
        var content = ""

        assertTrue(
            fence.writeIfCurrent(key, 0L, "refreshed.nb") {
                content = "refreshed"
            }
        )
        assertEquals("refreshed", content)
        assertEquals(null, fence.state(key).fileName)
    }
}
