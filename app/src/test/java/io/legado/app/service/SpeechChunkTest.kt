package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChunkTest {
    @Test fun paragraphStaysWholeAndOversizedInputLosesNoCharacters() {
        val short = "跨页也要完整读养老院，不能按页面切开。"
        assertEquals(short.length, speechChunkEnd(short, 0, 4000))
        val text = "甲".repeat(3999) + "😀" + "长段落没有空格".repeat(900) + "。下一句继续。"
        var start = 0
        val chunks = buildList {
            while (start < text.length) {
                val end = speechChunkEnd(text, start, 4000)
                assertTrue(end > start && end - start <= 4000)
                add(text.substring(start, end))
                start = end
            }
        }
        assertEquals(text, chunks.joinToString(""))
        assertFalse(chunks.any { Character.isHighSurrogate(it.last()) || Character.isLowSurrogate(it.first()) })
        assertEquals(3999, chunks.first().length)
    }

    @Test fun requiredSplitPrefersExistingSentenceOrWordBoundary() {
        assertEquals(6, speechChunkEnd("hello world", 0, 8))
        assertEquals(4, speechChunkEnd("第一句。下一句还没读完", 0, 7))
    }
}
