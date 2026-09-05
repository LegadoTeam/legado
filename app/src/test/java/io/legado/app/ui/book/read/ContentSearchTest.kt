package io.legado.app.ui.book.read

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.regex.PatternSyntaxException

class ContentSearchTest {
    @Test
    fun literalSearchPreservesWhitespaceAndDoesNotInterpretRegex() {
        assertEquals(listOf(0..2), findContentMatches("a.b aXb", "a.b", false, false))
        assertEquals(listOf(1..1, 3..3), findContentMatches("a b c", " ", false, false))
        assertTrue(findContentMatches("abc", "", false, false).isEmpty())
        assertTrue(findContentMatches("abc", "absent", false, false).isEmpty())
    }

    @Test
    fun caseAndRegexOptionsAreIndependent() {
        assertEquals(listOf(0..4, 6..10), findContentMatches("Alpha alpha", "alpha", false, false))
        assertEquals(listOf(6..10), findContentMatches("Alpha alpha", "alpha", false, true))
        assertEquals(listOf(0..2, 4..6), findContentMatches("a.b AXb", "a.b", true, false))
        assertEquals(listOf(0..2), findContentMatches("a.b AXb", "a.b", true, true))
        assertEquals(listOf(0..0, 2..2), findContentMatches("\u00C9 \u00E9", "\u00E9", false, false))
    }

    @Test
    fun zeroWidthAndUnicodeOffsetsCanBePassedToAndroidSelection() {
        val ranges = findContentMatches("ab", "(?=a)|$", true, true)
        assertEquals(listOf(0, 2), ranges.map { it.first })
        assertTrue(ranges.all { it.last + 1 == it.first })
        assertEquals(listOf(2..3), findContentMatches("\uD83D\uDE00\u4F60\u597D", "\u4F60\u597D", false, true))
    }

    @Test(expected = PatternSyntaxException::class)
    fun invalidExpressionIsReported() {
        findContentMatches("chapter", "[", true, false)
    }

    @Test
    fun navigationWrapsInBothDirections() {
        assertEquals(0, cycleContentMatchIndex(2, 1, 3))
        assertEquals(2, cycleContentMatchIndex(0, -1, 3))
        assertEquals(-1, cycleContentMatchIndex(-1, 1, 0))
        assertEquals(0, cycleContentMatchIndex(0, -1, 1))
    }

    @Test
    fun longTextHasCompleteCountsAndSupportsCancellation() {
        assertEquals(10000, findContentMatches("text\n".repeat(10000), "text", false, true).size)
        var checks = 0
        try {
            findContentMatches("a".repeat(10000), "a", true, true) {
                if (++checks == 10) throw CancellationException()
            }
            throw AssertionError("Expected search cancellation")
        } catch (_: CancellationException) {
            assertEquals(10, checks)
        }
    }
}
