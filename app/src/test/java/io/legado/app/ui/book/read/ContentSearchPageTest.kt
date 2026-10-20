package io.legado.app.ui.book.read

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentSearchPageTest {
    @Test
    fun hitAtPageStartBelongsToFollowingPage() {
        val pages = listOf(12, 20, 8)
        assertEquals(0, contentSearchPageIndex(pages, 11))
        assertEquals(1, contentSearchPageIndex(pages, 12))
        assertEquals(1, contentSearchPageIndex(pages, 31))
        assertEquals(2, contentSearchPageIndex(pages, 32))
    }

    @Test
    fun zeroWidthHitAtChapterEndStaysOnLastPage() {
        assertEquals(2, contentSearchPageIndex(listOf(12, 20, 8), 40))
        assertEquals(0, contentSearchPageIndex(listOf(12), 0))
    }
}
