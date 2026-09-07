package io.legado.app.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpTextSearchTest {

    @Test
    fun `finds literal case insensitive non-overlapping ranges`() {
        assertEquals(listOf(0..4, 6..10), findTextRanges("JsLib jslib", "jslib"))
        assertEquals(listOf(0..1, 2..3), findTextRanges("aaaa", "aa"))
        assertEquals(listOf(0..2), findTextRanges("a.b aXb", "a.b"))
    }

    @Test
    fun `blank or missing query has no ranges`() {
        assertTrue(findTextRanges("help", " ").isEmpty())
        assertTrue(findTextRanges("help", "missing").isEmpty())
    }
}
