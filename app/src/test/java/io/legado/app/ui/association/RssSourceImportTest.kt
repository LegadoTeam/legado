package io.legado.app.ui.association

import com.google.gson.JsonSyntaxException
import io.legado.app.data.entities.RssSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.source.requireSourceUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RssSourceImportTest {

    @Test
    fun `parses a single rss source object`() {
        val result = parseRssSourceJson(
            """
            {
              "sourceUrl": "https://example.com/feed",
              "sourceName": "Example"
            }
            """.trimIndent()
        )

        assertTrue(result is RssSourceImportJson.Sources)
        val source = (result as RssSourceImportJson.Sources).items.single()
        assertEquals("https://example.com/feed", source.sourceUrl)
        assertEquals("Example", source.sourceName)
    }

    @Test
    fun `rejects a single object without a usable source url`() {
        val invalidSources = listOf(
            """{"sourceName":"Missing URL"}""",
            """{"sourceUrl":null,"sourceName":"Null URL"}""",
            """{"sourceUrl":"","sourceName":"Empty URL"}""",
            """{"sourceUrl":"   ","sourceName":"Blank URL"}""",
        )

        invalidSources.forEach { json ->
            val error = assertThrows(NoStackTraceException::class.java) {
                parseRssSourceJson(json)
            }
            assertEquals("不是订阅源", error.message)
        }
    }

    @Test
    fun `preserves rss source array imports`() {
        val result = parseRssSourceJson(
            """
            [
              {"sourceUrl":"https://example.com/one","sourceName":"One"},
              {"sourceUrl":"https://example.com/two","sourceName":"Two"}
            ]
            """.trimIndent()
        )

        assertTrue(result is RssSourceImportJson.Sources)
        assertEquals(
            listOf("https://example.com/one", "https://example.com/two"),
            (result as RssSourceImportJson.Sources).items.map { it.sourceUrl },
        )
    }

    @Test
    fun `rejects any rss source array item without a usable source url`() {
        val error = assertThrows(NoStackTraceException::class.java) {
            parseRssSourceJson(
                """
                [
                  {"sourceUrl":"https://example.com/one","sourceName":"One"},
                  {"sourceName":"Missing URL"}
                ]
                """.trimIndent()
            )
        }

        assertEquals("不是订阅源", error.message)
    }

    @Test
    fun `rejects a later rss source array item whose url is blank`() {
        val error = assertThrows(NoStackTraceException::class.java) {
            parseRssSourceJson(
                """
                [
                  {"sourceUrl":"https://example.com/valid","sourceName":"Valid"},
                  {"sourceUrl":"   ","sourceName":"Blank URL"}
                ]
                """.trimIndent()
            )
        }

        assertEquals("不是订阅源", error.message)
    }

    @Test
    fun `shared source url validator rejects empty and whitespace`() {
        listOf("", " ").forEach { sourceUrl ->
            assertThrows(NoStackTraceException::class.java) {
                RssSource(sourceUrl = sourceUrl).requireSourceUrl()
            }
        }
    }

    @Test
    fun `preserves source urls wrapper imports`() {
        val result = parseRssSourceJson(
            """
            {
              "sourceUrls": [
                "https://example.com/sources-one.json",
                "https://example.com/sources-two.json"
              ]
            }
            """.trimIndent()
        )

        assertTrue(result is RssSourceImportJson.SourceUrls)
        assertEquals(
            listOf(
                "https://example.com/sources-one.json",
                "https://example.com/sources-two.json",
            ),
            (result as RssSourceImportJson.SourceUrls).items,
        )
    }

    @Test
    fun `empty source urls wrapper does not become a single source`() {
        val result = parseRssSourceJson(
            """{"sourceUrls":[],"sourceUrl":"https://example.com/feed"}"""
        )

        assertTrue(result is RssSourceImportJson.SourceUrls)
        assertTrue((result as RssSourceImportJson.SourceUrls).items.isEmpty())
    }

    @Test
    fun `rejects null empty or blank source urls while keeping empty arrays`() {
        val invalidSourceUrls = listOf(
            """{"sourceUrls":null}""",
            """{"sourceUrls":[""]}""",
            """{"sourceUrls":["   "]}""",
        )

        invalidSourceUrls.forEach { json ->
            val error = assertThrows(NoStackTraceException::class.java) {
                parseRssSourceJson(json)
            }
            assertEquals("不是订阅源", error.message)
        }

        assertThrows(JsonSyntaxException::class.java) {
            parseRssSourceJson("""{"sourceUrls":[null]}""")
        }

        val empty = parseRssSourceJson("""{"sourceUrls":[]}""")
        assertTrue((empty as RssSourceImportJson.SourceUrls).items.isEmpty())
    }
}
