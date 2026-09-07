package io.legado.app.help

import io.legado.app.help.HighlightRuleMatcher.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightRuleMatcherTest {

    private val style = HighlightStyle(fill = 1)

    @Test
    fun `literal matching is non overlapping`() {
        val matches = HighlightRuleMatcher.match(
            "aXaXa",
            listOf(Rule(1, "aX", false, style))
        )

        assertEquals(listOf(0 to 2, 2 to 4), matches.map { it.start to it.end })
    }

    @Test
    fun `regex matching preserves offsets and title opt in`() {
        val matches = HighlightRuleMatcher.match(
            "第1章 第22章",
            listOf(Rule(7, "第\\d+章", true, style, applyToTitle = true))
        )

        assertEquals(listOf(0 to 3, 4 to 8), matches.map { it.start to it.end })
        assertTrue(matches.all { it.ruleId == 7L && it.applyToTitle })
    }

    @Test
    fun `title and body scopes preserve local anchors and chapter offsets`() {
        val matches = HighlightRuleMatcher.match(
            "Title\nBody",
            listOf(
                Rule(
                    1,
                    "^Title$",
                    true,
                    style,
                    applyToTitle = true,
                    applyToBody = false
                ),
                Rule(2, "^Body$", true, style),
                Rule(3, "Title\nBody", false, style, applyToTitle = true),
                Rule(4, "Body", false, style, applyToBody = false)
            ),
            titleLength = 6
        )

        assertEquals(
            listOf(1L to (0 to 5), 2L to (6 to 10), 3L to (0 to 10)),
            matches.map { it.ruleId to (it.start to it.end) }
        )
        assertTrue(matches.first().applyToTitle)
        assertFalse(matches.first().applyToBody)
        assertTrue(matches[1].applyToBody)
        assertFalse(matches[1].applyToTitle)
        assertTrue(matches.last().applyToTitle && matches.last().applyToBody)
    }

    @Test
    fun `zero width and invalid regexes are skipped`() {
        assertTrue(
            HighlightRuleMatcher.match("bbb", listOf(Rule(1, "a*", true, style))).isEmpty()
        )
        assertTrue(
            HighlightRuleMatcher.match("text", listOf(Rule(1, "[", true, style))).isEmpty()
        )
    }

    @Test
    fun `overlapping rules retain independent matches`() {
        val matches = HighlightRuleMatcher.match(
            "abcd",
            listOf(Rule(1, "abc", false, style), Rule(2, "bcd", false, style))
        )

        assertTrue(matches.any { it.ruleId == 1L && it.start == 0 && it.end == 3 })
        assertTrue(matches.any { it.ruleId == 2L && it.start == 1 && it.end == 4 })
        assertFalse(matches.any { it.applyToTitle })
    }

    @Test
    fun `catastrophic regex obeys its matching deadline`() {
        val startedAt = System.nanoTime()

        val result = HighlightRuleMatcher.matchDetailed(
            "a".repeat(10_000) + "!",
            listOf(Rule(1, "(a+)+$", true, style, timeoutMs = 5L))
        )

        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        assertTrue(result.matches.isEmpty())
        assertFalse(result.completed)
        assertTrue("regex matching took $elapsedMs ms", elapsedMs < 2_000L)
    }

    @Test
    fun `timed out regex marks its prefix matches incomplete`() {
        val result = HighlightRuleMatcher.matchDetailed(
            "ok" + "a".repeat(10_000) + "!",
            listOf(Rule(1, "ok|(a+)+$", true, style, timeoutMs = 5L))
        )

        assertEquals(listOf(0 to 2), result.matches.map { it.start to it.end })
        assertFalse(result.completed)
    }

    @Test
    fun `all rules share one chapter match limit`() {
        val result = HighlightRuleMatcher.matchDetailed(
            "aaaaa",
            listOf(
                Rule(1, "a", false, style),
                Rule(2, "a", false, style)
            ),
            maxMatches = 3
        )

        assertEquals(3, result.matches.size)
        assertTrue(result.matches.all { it.ruleId == 1L })
        assertFalse(result.completed)
    }

    @Test
    fun `literal matching stops when its task is cancelled`() {
        var checks = 0

        val matches = HighlightRuleMatcher.match(
            "a".repeat(100),
            listOf(Rule(1, "a", false, style)),
            shouldContinue = { ++checks < 3 }
        )

        assertEquals(1, matches.size)
        assertEquals(3, checks)
    }
}
