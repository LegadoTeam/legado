package io.legado.app.ui.widget.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordTokenizerTest {
    @Test fun findsOnlyTheCurrentTokenWithoutCopyingTheDocument() {
        val suffix = " target"
        var reads = 0
        val text = object : CharSequence {
            override val length = 1_000_000 + suffix.length
            override fun get(index: Int): Char {
                reads++
                check(index >= 1_000_000) { "Read an unrelated document prefix" }
                return suffix[index - 1_000_000]
            }
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("Copied document")
            override fun toString(): String = error("Copied document")
        }
        assertEquals(1_000_001, KeywordTokenizer().findTokenStart(text, text.length))
        assertTrue(reads <= suffix.length)
    }

    @Test fun retainsCompletionBoundariesAtTheStartMiddleAndEnd() {
        val tokenizer = KeywordTokenizer()
        assertEquals(0, tokenizer.findTokenStart("", 0))
        assertEquals(0, tokenizer.findTokenStart("word", 4))
        assertEquals(0, tokenizer.findTokenStart(" word", 5))
        assertEquals(2, tokenizer.findTokenStart("a word", 6))
        assertEquals(2, tokenizer.findTokenStart("a\nword", 6))
        assertEquals(2, tokenizer.findTokenStart("a(word tail", 6))
        assertEquals(1, tokenizer.findTokenStart("a ", 2))
        assertEquals(2, tokenizer.findTokenStart("a word", 2))
    }
}
