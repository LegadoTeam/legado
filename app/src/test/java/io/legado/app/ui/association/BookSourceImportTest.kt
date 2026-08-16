package io.legado.app.ui.association

import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `source replacement runs in rule order without changing the original`() {
        val source = BookSource(
            bookSourceUrl = "https://example.com/old",
            bookSourceName = "Old name",
        )
        val rules = listOf(
            ReplaceRule(
                name = "first",
                pattern = "Old name",
                replacement = "Middle name",
                scopeSource = true,
                isRegex = false,
                order = 1,
            ),
            ReplaceRule(
                name = "second",
                pattern = "Middle name",
                replacement = "New name",
                scopeSource = true,
                isRegex = false,
                order = 2,
            ),
        )

        val candidate = prepareBookSourceImportCandidate(source, rules)

        assertEquals("Old name", candidate.original.bookSourceName)
        assertEquals("New name", candidate.replaced?.bookSourceName)
        assertTrue(candidate.canImport(useReplacement = true))
    }

    @Test
    fun `source replacement honors include and exclude scope`() {
        val source = BookSource(
            bookSourceUrl = "https://example.com/source",
            bookSourceName = "Example",
        )
        val excluded = ReplaceRule(
            pattern = "Example",
            replacement = "Changed",
            scope = "Example",
            excludeScope = "example.com/source",
            scopeSource = true,
            isRegex = false,
        )
        val unrelated = excluded.copy(excludeScope = null, scope = "Other")
        val included = excluded.copy(excludeScope = null)

        assertEquals(
            null,
            prepareBookSourceImportCandidate(source, listOf(excluded, unrelated)).replacedJson,
        )
        assertEquals(
            "Changed",
            prepareBookSourceImportCandidate(source, listOf(included)).replaced?.bookSourceName,
        )
    }

    @Test
    fun `invalid replaced source remains previewable but cannot be imported`() {
        val source = BookSource(
            bookSourceUrl = "https://example.com/source",
            bookSourceName = "Example",
        )
        val removeUrl = ReplaceRule(
            pattern = "https://example.com/source",
            replacement = "",
            scopeSource = true,
            isRegex = false,
        )

        val candidate = prepareBookSourceImportCandidate(source, listOf(removeUrl))

        assertEquals(
            "",
            GSON.fromJsonObject<BookSource>(candidate.replacedJson)
                .getOrThrow()
                .bookSourceUrl,
        )
        assertTrue(candidate.replacementError?.isNotBlank() == true)
        assertFalse(candidate.canImport(useReplacement = true))
        assertTrue(candidate.canImport(useReplacement = false))
    }

    @Test
    fun `replace rule source scope is backward compatible and independent`() {
        val legacy = GSON.fromJsonObject<ReplaceRule>(
            """{"pattern":"x","scopeTitle":true,"scopeContent":true}"""
        ).getOrThrow()
        assertFalse(legacy.scopeSource)

        val rule = ReplaceRule(
            pattern = "x",
            scopeTitle = true,
            scopeSource = true,
            scopeContent = true,
        )
        val restored = GSON.fromJsonObject<ReplaceRule>(GSON.toJson(rule)).getOrThrow()
        assertTrue(restored.scopeTitle)
        assertTrue(restored.scopeSource)
        assertTrue(restored.scopeContent)
    }
}
