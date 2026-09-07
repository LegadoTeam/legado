package io.legado.app.ui.book.toc

import io.legado.app.data.entities.BookChapter
import io.legado.app.model.localBook.EpubTocNode
import org.junit.Assert.*
import org.junit.Test

class EpubTocListStateTest {
    private val titles = listOf("第一卷", "第一章", "第一节", "第二卷", "第一章", "番外 一", "第三卷", "第一章", "番外 二", "番外 三")
    private val depths = listOf(0, 1, 2, 0, 1, 0, 0, 1, 0, 0)
    private val parents = listOf(null, 0, 1, null, 3, null, null, 6, null, null)
    private val chapters = titles.mapIndexed { i, title ->
        BookChapter(index = i, title = title, url = "Text/$i.html", bookUrl = "old-import")
    }
    private val toc = titles.mapIndexed { i, title -> EpubTocNode(i, parents[i], depths[i], title, chapters[i].url) }

    @Test
    fun originalHierarchyKeepsStandaloneExtrasAndReadingIdentities() {
        val state = state()
        val rows = state.showNormal(2)
        assertEquals(titles, rows.map { it.chapter.title })
        assertEquals(depths, rows.map { it.depth })
        assertEquals(chapters.indices.toList(), rows.map { it.readingChapter!!.index })
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        assertTrue(rows[0] is TocListItem.Volume)
        assertTrue(rows[1] is TocListItem.Volume)
        assertTrue(listOf(5, 8, 9).all { rows[it] is TocListItem.Chapter && rows[it].depth == 0 })
        assertTrue(chapters.none { it.isVolume }) // Enrich old imports without mutating their stored rows.
    }

    @Test
    fun nestedCollapseSearchAndCurrentChapterRevealUseEveryAncestor() {
        val state = state()
        assertTrue(state.toggleVolume(-2))
        assertTrue(state.toggleVolume(-1))
        assertEquals(8, state.showNormal(2).size)
        assertEquals(0, state.findFallbackVisiblePositionForChapterIndex(2))
        assertTrue((state.visibleItems.first() as TocListItem.Volume).containsCurrentChapter)
        val search = state.showSearch(state.searchIndexes("第一节"), 2)
        assertEquals(titles.take(3), search.map { it.chapter.title })
        assertEquals(listOf(0, 1, 2), search.map { it.depth })
        assertTrue(search.filterIsInstance<TocListItem.Volume>().all { !it.canToggle })
        assertEquals(8, state.showNormal(2).size)
        assertTrue(state.expandVolumeContainingChapter(2))
        assertEquals(10, state.showNormal(2).size)
        assertEquals(2, state.findVisiblePositionByChapterIndex(2))
    }

    @Test
    fun collapsedDefaultRevealsCurrentPathAndKeepsOtherBranchesClosed() {
        val state = state(expanded = false)
        assertFalse(state.isVolumeCollapsed(-1))
        assertFalse(state.isVolumeCollapsed(-2))
        assertTrue(state.isVolumeCollapsed(-4))
        assertTrue(state.isVolumeCollapsed(-7))
        assertEquals(listOf(0, 1, 2, 3, 5, 6, 8, 9), state.showNormal(2).map { it.readingChapter!!.index })
    }

    @Test
    fun reverseOnlyChangesSiblingPresentationAndPreservesOldReversedChapterIndexes() {
        val oldReversed = chapters.reversed().mapIndexed { i, chapter -> chapter.copy(index = i) }
        val state = TocListState()
        state.setFullChapters(oldReversed, reverseOrder = true, epubToc = toc)
        val rows = state.showNormal(7)
        assertEquals(listOf(9, 8, 6, 7, 5, 3, 4, 0, 1, 2).map(titles::get), rows.map { it.chapter.title })
        assertEquals(listOf(0, 1, 3, 2, 4, 6, 5, 9, 8, 7), rows.map { it.readingChapter!!.index })
        assertEquals(chapters.reversed().map { it.url }, oldReversed.map { it.url })
        state.setFullChapters(oldReversed, reverseOrder = false, epubToc = toc)
        assertEquals(titles, state.showNormal(7).map { it.chapter.title })
        assertEquals(7, state.visibleItems[2].readingChapter!!.index)
    }

    @Test
    fun resourceLessParentsAndAliasesHaveDistinctNavigationIdentity() {
        val chapter = chapters.first().copy(index = 42)
        val tree = listOf(
            EpubTocNode(0, null, 0, "Container", null),
            EpubTocNode(1, 0, 1, "Heading", chapter.url),
            EpubTocNode(2, 1, 2, "Alias", chapter.url),
            EpubTocNode(3, null, 0, "Unavailable", "missing.xhtml"),
        )
        val state = TocListState()
        state.setFullChapters(listOf(chapter), false, epubToc = tree)
        val rows = state.showNormal(42)
        assertEquals(4, rows.size)
        assertEquals(4, rows.map { it.key }.toSet().size)
        assertNull(rows[0].readingChapter)
        assertNull(rows[3].readingChapter)
        assertEquals(listOf(42, 42), rows.subList(1, 3).map { it.readingChapter!!.index })
        assertTrue(state.toggleVolume(-1))
        assertEquals(listOf("Container", "Unavailable"), state.showNormal(42).map { it.chapter.title })
        assertTrue(state.expandVolumeContainingChapter(42))
        assertEquals(4, state.showNormal(42).size)
    }

    private fun state(expanded: Boolean = true) = TocListState().apply {
        setFullChapters(chapters, false, defaultExpanded = expanded, currentChapterIndex = 2, epubToc = toc)
    }
}
