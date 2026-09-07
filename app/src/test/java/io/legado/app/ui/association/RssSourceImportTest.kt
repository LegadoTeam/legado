package io.legado.app.ui.association

import com.google.gson.JsonSyntaxException
import io.legado.app.data.entities.ReplaceRule
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
    fun `single source parser accepts objects and one-item arrays`() {
        val objectSource = parseSingleRssSourceJson(
            """{"sourceUrl":"https://example.com/object","sourceName":"Object"}"""
        )
        val arraySource = parseSingleRssSourceJson(
            """[{"sourceUrl":"https://example.com/array","sourceName":"Array"}]"""
        )

        assertEquals("https://example.com/object", objectSource.sourceUrl)
        assertEquals("https://example.com/array", arraySource.sourceUrl)
    }

    @Test
    fun `single source parser keeps incomplete objects editable`() {
        val source = parseSingleRssSourceJson("""{"sourceName":"Draft"}""")

        assertEquals("", source.sourceUrl)
        assertEquals("Draft", source.sourceName)
    }

    @Test
    fun `single source parser rejects empty and multi-item arrays`() {
        listOf(
            "[]",
            """[
                {"sourceUrl":"https://example.com/one"},
                {"sourceUrl":"https://example.com/two"}
            ]""".trimIndent(),
        ).forEach { json ->
            val error = assertThrows(NoStackTraceException::class.java) {
                parseSingleRssSourceJson(json)
            }
            assertEquals("不是单个订阅源", error.message)
        }
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

    @Test
    fun `source replacement runs in rule order without changing the original`() {
        val source = RssSource(
            sourceUrl = "https://example.com/old",
            sourceName = "Old feed",
        )
        val rules = listOf(
            ReplaceRule(
                name = "first",
                pattern = "Old feed",
                replacement = "Middle feed",
                scopeSource = true,
                isRegex = false,
                order = 1,
            ),
            ReplaceRule(
                name = "second",
                pattern = "Middle feed",
                replacement = "New feed",
                scopeSource = true,
                isRegex = false,
                order = 2,
            ),
        )

        val candidate = prepareRssSourceImportCandidate(source, rules)

        assertEquals("Old feed", candidate.original.sourceName)
        assertEquals("New feed", candidate.replaced?.sourceName)
        assertTrue(candidate.canImport(useReplacement = true))
    }

    @Test
    fun `source replacement honors include and exclude scope`() {
        val source = RssSource(
            sourceUrl = "https://example.com/feed",
            sourceName = "Example",
        )
        val excluded = ReplaceRule(
            pattern = "Example",
            replacement = "Changed",
            scope = "Example",
            excludeScope = "example.com/feed",
            scopeSource = true,
            isRegex = false,
        )
        val unrelated = excluded.copy(excludeScope = null, scope = "Other")
        val included = excluded.copy(excludeScope = null)

        assertEquals(
            null,
            prepareRssSourceImportCandidate(source, listOf(excluded, unrelated)).replacedJson,
        )
        assertEquals(
            "Changed",
            prepareRssSourceImportCandidate(source, listOf(included)).replaced?.sourceName,
        )
    }

    @Test
    fun `invalid replaced source remains previewable but cannot be imported`() {
        val source = RssSource(
            sourceUrl = "https://example.com/feed",
            sourceName = "Example",
        )
        val rule = ReplaceRule(
            pattern = "https://example.com/feed",
            replacement = "",
            scopeSource = true,
            isRegex = false,
        )

        val candidate = prepareRssSourceImportCandidate(source, listOf(rule))

        assertEquals(null, candidate.replaced)
        assertTrue(candidate.replacedJson?.contains("\"sourceUrl\"") == true)
        assertTrue(candidate.replacementError?.isNotBlank() == true)
        assertTrue(candidate.canImport(useReplacement = false))
        assertTrue(!candidate.canImport(useReplacement = true))
    }

    @Test
    fun `rule manager refreshes all candidates and keeps edited draft`() {
        val candidates = listOf(
            prepareRssSourceImportCandidate(
                RssSource("https://example.com/one", "First Feed"),
                emptyList(),
            ),
            prepareRssSourceImportCandidate(
                RssSource("https://example.com/two", "Second Feed"),
                emptyList(),
            ),
        )
        val rules = listOf(
            ReplaceRule(
                pattern = " Feed",
                replacement = " Updated",
                scopeSource = true,
                isRegex = false,
            )
        )

        val refreshed = refreshRssSourceImportCandidates(
            candidates,
            editedIndex = 0,
            editedSource = RssSource("https://example.com/one", "Draft Feed"),
            rules = rules,
        )

        assertEquals(
            listOf("Draft Feed", "Second Feed"),
            refreshed.map { it.original.sourceName },
        )
        assertEquals(
            listOf("Draft Updated", "Second Updated"),
            refreshed.map { it.replaced?.sourceName },
        )
    }
}
