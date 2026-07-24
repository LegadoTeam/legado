package io.legado.app.ui.association

import io.legado.app.exception.NoStackTraceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSourceImportTest {

    @Test
    fun `parses a single book source object`() {
        val result = parseBookSourceJson(
            """
            {
              "bookSourceUrl": "https://example.com/source",
              "bookSourceName": "Example"
            }
            """.trimIndent()
        )

        assertTrue(result is BookSourceImportJson.Sources)
        val source = (result as BookSourceImportJson.Sources).items.single()
        assertEquals("https://example.com/source", source.bookSourceUrl)
        assertEquals("Example", source.bookSourceName)
    }

    @Test
    fun `rejects a single object without a usable source url`() {
        val invalidSources = listOf(
            """{"bookSourceName":"Missing URL"}""",
            """{"bookSourceUrl":null,"bookSourceName":"Null URL"}""",
            """{"bookSourceUrl":"","bookSourceName":"Empty URL"}""",
            """{"bookSourceUrl":"   ","bookSourceName":"Blank URL"}""",
        )

        invalidSources.forEach { json ->
            val error = assertThrows(NoStackTraceException::class.java) {
                parseBookSourceJson(json)
            }
            assertEquals("不是书源", error.message)
        }
    }

    @Test
    fun `rejects any invalid book source array item`() {
        val invalidArrays = listOf(
            """
            [
              {"bookSourceUrl":"https://example.com/one","bookSourceName":"One"},
              {"bookSourceName":"Missing URL"}
            ]
            """.trimIndent(),
            """
            [
              {"bookSourceUrl":"https://example.com/one","bookSourceName":"One"},
              {"bookSourceUrl":"   ","bookSourceName":"Blank URL"}
            ]
            """.trimIndent(),
        )

        invalidArrays.forEach { json ->
            val error = assertThrows(NoStackTraceException::class.java) {
                parseBookSourceJson(json)
            }
            assertEquals("不是书源", error.message)
        }
    }

    @Test
    fun `preserves valid book source array imports`() {
        val result = parseBookSourceJson(
            """
            [
              {"bookSourceUrl":"https://example.com/one","bookSourceName":"One"},
              {"bookSourceUrl":"https://example.com/two","bookSourceName":"Two"}
            ]
            """.trimIndent()
        )

        assertEquals(
            listOf("https://example.com/one", "https://example.com/two"),
            (result as BookSourceImportJson.Sources).items.map { it.bookSourceUrl },
        )
    }

    @Test
    fun `validates source urls wrapper while preserving empty arrays`() {
        val result = parseBookSourceJson(
            """{"sourceUrls":["https://example.com/one.json","https://example.com/two.json"]}"""
        )
        assertEquals(
            listOf("https://example.com/one.json", "https://example.com/two.json"),
            (result as BookSourceImportJson.SourceUrls).items,
        )

        val invalidSourceUrls = listOf(
            """{"sourceUrls":null}""",
            """{"sourceUrls":[null]}""",
            """{"sourceUrls":[""]}""",
            """{"sourceUrls":["   "]}""",
        )
        invalidSourceUrls.forEach { json ->
            assertThrows(Exception::class.java) {
                parseBookSourceJson(json)
            }
        }

        val empty = parseBookSourceJson("""{"sourceUrls":[]}""")
        assertTrue((empty as BookSourceImportJson.SourceUrls).items.isEmpty())

        assertThrows(NoStackTraceException::class.java) {
            parseBookSourceJson(
                """{"sourceUrls":["https://example.com/nested.json"]}""",
                allowSourceUrls = false,
            )
        }
    }
}
