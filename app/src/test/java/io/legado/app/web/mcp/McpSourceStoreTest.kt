package io.legado.app.web.mcp

import io.legado.app.model.jsSource.JsSourceUpsert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class McpSourceStoreTest {

    @Test
    fun parsesBomPrefixedDeclarativeSource() {
        val source = McpSourceStore.parseDeclarative(
            "\uFEFF  {\"bookSourceName\":\"test\",\"bookSourceUrl\":\"https://example.test\"}"
        )

        assertEquals("test", source.bookSourceName)
        assertEquals("https://example.test", source.bookSourceUrl)
        assertNull(source.mainJs)
    }

    @Test
    fun rejectsJavaScriptSourceInJsonMode() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            McpSourceStore.parseDeclarative(
                "{\"bookSourceName\":\"test\",\"bookSourceUrl\":\"https://example.test\",\"mainJs\":\"x\"}"
            )
        }

        assertTrue(error.message.orEmpty().contains("format=js"))
    }

    @Test
    fun rejectsOversizedJson() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            McpSourceStore.parseDeclarative("x".repeat(JsSourceUpsert.MAX_SOURCE_BYTES + 1))
        }

        assertTrue(error.message.orEmpty().contains("1 MiB"))
    }
}
