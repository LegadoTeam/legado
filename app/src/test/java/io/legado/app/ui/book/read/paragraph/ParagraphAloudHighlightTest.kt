package io.legado.app.ui.book.read.paragraph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphAloudHighlightTest {
    private fun highlight(position: Int, itemId: String = "text") =
        ParagraphAloudHighlight("book", "chapter", itemId, position)

    @Test
    fun followsOneCompleteParagraphAcrossPageBoundaries() {
        val item = ParagraphItem(
            "text", "abcdefgh", chapterPosition = 20,
            positionRanges = listOf(
                ParagraphPositionRange(0, 3, 20),
                ParagraphPositionRange(3, 8, 23),
            ),
        )
        assertEquals(0, highlight(20).textStart(item))
        assertEquals(3, highlight(23).textStart(item))
        assertEquals(7, highlight(27).textStart(item))
        assertNull(highlight(28).textStart(item))
    }

    @Test
    fun laterSpeechChunksKeepTheirOffsetWithinTheSameLongParagraph() {
        val item = ParagraphItem(
            "text", "a".repeat(5000), chapterPosition = 100,
            positionRanges = listOf(ParagraphPositionRange(0, 5000, 100)),
        )
        assertEquals(0, highlight(100).textStart(item))
        assertEquals(4000, highlight(4100).textStart(item))
        assertEquals(4999, highlight(5099).textStart(item))
        assertNull(highlight(5100).textStart(item))
    }

    @Test
    fun mapsHtmlTextRangesWithoutHighlightingCanonicalGaps() {
        val item = ParagraphItem(
            "text", "abcdef", chapterPosition = 10,
            positionRanges = listOf(
                ParagraphPositionRange(0, 2, 10),
                ParagraphPositionRange(2, 4, 20),
                ParagraphPositionRange(4, 6, 80),
            ),
        )
        assertEquals(1, highlight(11).textStart(item))
        assertEquals(2, highlight(20).textStart(item))
        assertEquals(5, highlight(81).textStart(item))
        listOf(9, 12, 19, 22, 79, 82).forEach { assertNull(highlight(it).textStart(item)) }
    }

    @Test
    fun titleUsesTheSameDisplayCoordinateMapping() {
        val item = ParagraphItem(
            "title", "第十二章", chapterPosition = 0, isTitle = true,
            positionRanges = listOf(ParagraphPositionRange(0, 4, 0)),
        )
        assertEquals(0, highlight(0, "title").textStart(item))
        assertEquals(2, highlight(2, "title").textStart(item))
        assertNull(highlight(4, "title").textStart(item))
    }

    @Test
    fun repeatedItemIdsAreStillScopedToBookAndChapter() {
        val position = highlight(10)
        assertTrue(position.matchesChapter("book", "chapter"))
        assertFalse(position.matchesChapter("another-book", "chapter"))
        assertFalse(position.matchesChapter("book", "another-chapter"))
        assertFalse(position.matchesChapter(null, "chapter"))
        assertFalse(position.matchesChapter("book", null))
    }

    @Test
    fun excludesOtherItemsImagesAndEmptyText() {
        val item = ParagraphItem("text", "abc", chapterPosition = 10)
        assertNull(highlight(10, "other").textStart(item))
        assertNull(highlight(10).textStart(item.copy(imageSrc = "picture.jpg")))
        assertNull(highlight(10).textStart(item.copy(text = "")))
        assertNull(highlight(-1).textStart(item))
    }

    @Test
    fun plainItemsWithoutRangesUseTheirChapterStartAndExcludeTheEnd() {
        val item = ParagraphItem("text", "abc", chapterPosition = 10)
        assertEquals(0, highlight(10).textStart(item))
        assertEquals(2, highlight(12).textStart(item))
        assertNull(highlight(9).textStart(item))
        assertNull(highlight(13).textStart(item))
    }

    @Test
    fun boundsLargeCanonicalPositionsWithoutIntegerOverflow() {
        val item = ParagraphItem("text", "abcd", chapterPosition = Int.MAX_VALUE - 2)
        assertEquals(2, highlight(Int.MAX_VALUE).textStart(item))
        assertNull(highlight(0).textStart(item))
        val mapped = item.copy(positionRanges = listOf(ParagraphPositionRange(0, 4, Int.MAX_VALUE - 2)))
        assertEquals(2, highlight(Int.MAX_VALUE).textStart(mapped))
        assertNull(highlight(0).textStart(mapped))
    }

    @Test
    fun utf16OffsetsDoNotSplitAnEmojiSurrogatePair() {
        val item = ParagraphItem(
            "text", "甲\uD83D\uDE00乙", chapterPosition = 100,
            positionRanges = listOf(ParagraphPositionRange(0, 4, 100)),
        )
        assertEquals(0, highlight(100).textStart(item))
        assertEquals(1, highlight(101).textStart(item))
        assertEquals(1, highlight(102).textStart(item))
        assertEquals(3, highlight(103).textStart(item))
        assertNull(highlight(104).textStart(item))
    }
}
