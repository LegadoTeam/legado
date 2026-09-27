package io.legado.app.ui.book.toc

import io.legado.app.model.localBook.PdfOutlineNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PdfOutlineListStateTest {
    private val nodes = listOf(
        PdfOutlineNode(0, null, 0, "第一部分", null),
        PdfOutlineNode(1, 0, 1, "章节", 3),
        PdfOutlineNode(2, 1, 2, "同页小节", 3),
        PdfOutlineNode(3, 0, 1, "另一章", 7),
        PdfOutlineNode(4, null, 0, "前言", 0),
    )

    @Test
    fun searchShowsAncestorsWithoutLosingCollapsedState() {
        val state = PdfOutlineListState(nodes, false)
        assertEquals(listOf(0, 4), state.items(null).map { it.node.id })
        val matches = state.items("小节")
        assertEquals(listOf(0, 1, 2), matches.map { it.node.id })
        assertFalse(matches.any { it.canToggle || it.collapsed })
        assertEquals(listOf(0, 4), state.items(null).map { it.node.id })
        state.toggle(0)
        assertEquals(listOf(0, 1, 3, 4), state.items(null).map { it.node.id })
        state.toggle(1)
        assertEquals(nodes, state.items(null).map { it.node })
    }

    @Test
    fun reverseKeepsParentsBeforeChildrenAndDoesNotChangeDestinations() {
        val state = PdfOutlineListState(nodes, true)
        assertEquals(listOf(4, 0, 3, 1, 2), state.items(null, true).map { it.node.id })
        assertEquals(listOf(0, null, 7, 3, 3), state.items(null, true).map { it.node.pageIndex })
        assertEquals(nodes, state.items(null).map { it.node })
    }
}
