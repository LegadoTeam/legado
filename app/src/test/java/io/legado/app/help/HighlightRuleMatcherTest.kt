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

        val matches = HighlightRuleMatcher.match(
            "a".repeat(10_000) + "!",
            listOf(Rule(1, "(a+)+$", true, style, timeoutMs = 5L))
        )

        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        assertTrue(matches.isEmpty())
        assertTrue("regex matching took $elapsedMs ms", elapsedMs < 2_000L)
    }

    @Test
    fun `all rules share one chapter match limit`() {
        val matches = HighlightRuleMatcher.match(
            "aaaaa",
            listOf(
                Rule(1, "a", false, style),
                Rule(2, "a", false, style)
            ),
            maxMatches = 3
        )

        assertEquals(3, matches.size)
        assertTrue(matches.all { it.ruleId == 1L })
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
