package io.legado.app.ui.book.toc

import io.legado.app.data.entities.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TocListStateTest {

    @Test
    fun `book without volumes stays flat`() {
        val state = stateOf(listOf(chapter(0), chapter(1), chapter(2)))

        val items = state.showNormal(currentChapterIndex = 1)

        assertEquals(listOf("chapter:0", "chapter:1", "chapter:2"), items.keys())
        assertEquals(listOf(0, 0, 0), items.map { it.depth })
    }

    @Test
    fun `default keeps all volumes expanded`() {
        val state = stateOf(
            listOf(
                volume(0, "First"), chapter(1), chapter(2),
                volume(3, "Second"), chapter(4), chapter(5),
            ),
        )

        val items = state.showNormal(currentChapterIndex = 4)

        assertFalse(state.isVolumeCollapsed(0))
        assertFalse(state.isVolumeCollapsed(3))
        assertEquals(
            listOf("volume:0", "chapter:1", "chapter:2", "volume:3", "chapter:4", "chapter:5"),
            items.keys(),
        )
        assertEquals(4, state.findVisiblePositionByChapterIndex(4))
    }

    @Test
    fun `toggle changes visible rows and preserves current group marker`() {
        val state = stateOf(
            listOf(volume(0, "First"), chapter(1), chapter(2)),
        )

        assertTrue(state.toggleVolume(0))
        val items = state.showNormal(currentChapterIndex = 1)

        assertEquals(listOf("volume:0"), items.keys())
        assertTrue((items.single() as TocListItem.Volume).containsCurrentChapter)
    }

    @Test
    fun `empty volume remains actionable instead of pretending to collapse`() {
        val state = stateOf(
            listOf(volume(0, "Empty"), volume(1, "Second"), chapter(2)),
        )

        val items = state.showNormal(currentChapterIndex = 2)

        assertFalse(state.isVolumeCollapsed(0))
        assertFalse(state.toggleVolume(0))
        assertEquals(listOf("volume:0", "volume:1", "chapter:2"), items.keys())
        val emptyVolume = items.first() as TocListItem.Volume
        assertEquals(0, emptyVolume.chapterCount)
        assertFalse(emptyVolume.canToggle)
    }

    @Test
    fun `hidden chapter falls back to parent and can expand it`() {
        val state = stateOf(
            listOf(volume(0, "First"), chapter(1), volume(2, "Second"), chapter(3)),
        )
        assertTrue(state.toggleVolume(2))
        state.showNormal(currentChapterIndex = 1)

        assertEquals(2, state.findFallbackVisiblePositionForChapterIndex(3))
        assertTrue(state.expandVolumeContainingChapter(3))
        state.showNormal(currentChapterIndex = 1)
        assertEquals(3, state.findVisiblePositionByChapterIndex(3))
    }

    @Test
    fun `search adds one parent context and keeps collapse state`() {
        val all = listOf(
            volume(0, "First"), chapter(1), chapter(2),
            volume(3, "Second"), chapter(4), chapter(5),
        )
        val state = stateOf(all)
        assertTrue(state.toggleVolume(3))
        assertTrue(state.isVolumeCollapsed(3))

        val items = state.showSearch(listOf(4, 5), currentChapterIndex = 1)

        assertEquals(listOf("volume:3", "chapter:4", "chapter:5"), items.keys())
        val volumeItem = items.first() as TocListItem.Volume
        assertEquals(2, volumeItem.matchedCount)
        assertEquals(2, volumeItem.chapterCount)
        assertFalse(volumeItem.canToggle)
        assertTrue(state.isVolumeCollapsed(3))
    }

    @Test
    fun `search volume title and child does not duplicate parent`() {
        val all = listOf(volume(0, "First"), chapter(1), chapter(2))
        val state = stateOf(all)

        val items = state.showSearch(listOf(0, 2), currentChapterIndex = 1)

        assertEquals(listOf("volume:0", "chapter:2"), items.keys())
        val volumeItem = items.first() as TocListItem.Volume
        assertTrue(volumeItem.matchedSelf)
        assertEquals(1, volumeItem.matchedCount)
    }

    @Test
    fun `volume title match can stand without child matches`() {
        val onlyVolume = volume(0, "First")
        val state = stateOf(listOf(onlyVolume, chapter(1)))

        val items = state.showSearch(listOf(onlyVolume.index), currentChapterIndex = 1)

        assertEquals(listOf("volume:0"), items.keys())
        val volumeItem = items.single() as TocListItem.Volume
        assertTrue(volumeItem.matchedSelf)
        assertEquals(0, volumeItem.matchedCount)
    }

    @Test
    fun `loose chapters stay at depth zero`() {
        val state = stateOf(
            listOf(chapter(0), volume(1, "First"), chapter(2), chapter(3)),
        )

        val items = state.showNormal(currentChapterIndex = 2)

        assertEquals(listOf("chapter:0", "volume:1", "chapter:2", "chapter:3"), items.keys())
        assertEquals(listOf(0, 0, 1, 1), items.map { it.depth })
    }

    @Test
    fun `refresh preserves collapse state until an explicit reset`() {
        val chapters = listOf(
            volume(0, "First"), chapter(1),
            volume(2, "Second"), chapter(3),
        )
        val state = stateOf(chapters)
        assertTrue(state.toggleVolume(2))

        state.setFullChapters(chapters, reverseOrder = false, resetCollapse = false)
        state.showNormal(currentChapterIndex = 1)
        assertTrue(state.isVolumeCollapsed(2))

        state.setFullChapters(chapters, reverseOrder = false, resetCollapse = true)
        state.showNormal(currentChapterIndex = 3)
        assertFalse(state.isVolumeCollapsed(0))
        assertFalse(state.isVolumeCollapsed(2))
    }

    @Test
    fun `new volume defaults to expanded during a non resetting refresh`() {
        val initial = listOf(volume(0, "First"), chapter(1))
        val state = stateOf(initial)
        assertTrue(state.toggleVolume(0))
        val refreshed = initial + listOf(volume(2, "Second"), chapter(3))

        state.setFullChapters(refreshed, reverseOrder = false, resetCollapse = false)
        val items = state.showNormal(currentChapterIndex = 1)

        assertTrue(state.isVolumeCollapsed(0))
        assertFalse(state.isVolumeCollapsed(2))
        assertEquals(listOf("volume:0", "volume:2", "chapter:3"), items.keys())
    }

    @Test
    fun `reverse order assigns preceding chapters to trailing volume headers`() {
        val reversed = listOf(
            chapter(0, "C3"),
            volume(1, "V2"),
            chapter(2, "C2"),
            chapter(3, "C1"),
            volume(4, "V1"),
        )
        val state = stateOf(reversed, reverse = true)

        val items = state.showNormal(currentChapterIndex = 0)

        assertEquals(
            listOf("volume:1", "chapter:0", "volume:4", "chapter:2", "chapter:3"),
            items.keys(),
        )
        assertEquals(1, state.parentVolumeIndexOf(0))
        assertEquals(4, state.parentVolumeIndexOf(2))
        assertEquals(4, state.parentVolumeIndexOf(3))
    }

    @Test
    fun `reverse search keeps volume before its matching child`() {
        val reversed = listOf(
            chapter(0, "C3"),
            volume(1, "V2"),
            chapter(2, "C2"),
            chapter(3, "C1"),
            volume(4, "V1"),
        )
        val state = stateOf(reversed, reverse = true)

        val items = state.showSearch(listOf(reversed[3].index), currentChapterIndex = 0)

        assertEquals(listOf("volume:4", "chapter:3"), items.keys())
    }

    @Test
    fun `switching direction rebuilds groups and expands all volumes`() {
        val forward = listOf(volume(0, "V1"), chapter(1), volume(2, "V2"), chapter(3))
        val state = stateOf(forward)
        val reversed = listOf(chapter(0, "C2"), volume(1, "V2"), chapter(2, "C1"), volume(3, "V1"))

        state.setFullChapters(reversed, reverseOrder = true, resetCollapse = false)
        val items = state.showNormal(currentChapterIndex = 0)

        assertEquals(listOf("volume:1", "chapter:0", "volume:3", "chapter:2"), items.keys())
        assertFalse(state.isVolumeCollapsed(1))
        assertFalse(state.isVolumeCollapsed(3))
    }

    @Test
    fun `item keys remain unique across a mixed list`() {
        val state = stateOf(
            listOf(chapter(0), volume(1, "V1"), chapter(2), volume(3, "Empty")),
        )

        val keys = state.showNormal(currentChapterIndex = 2).keys()

        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `clear invalidates full and visible directory state`() {
        val state = stateOf(listOf(volume(0, "V1"), chapter(1)))
        state.showNormal(currentChapterIndex = 1)

        state.clear()

        assertFalse(state.hasFullChapters())
        assertTrue(state.visibleItems.isEmpty())
        assertEquals(null, state.parentVolumeIndexOf(1))
    }

    private fun stateOf(
        chapters: List<BookChapter>,
        reverse: Boolean = false,
    ): TocListState {
        return TocListState().apply {
            setFullChapters(
                chapters = chapters,
                reverseOrder = reverse,
                resetCollapse = true,
            )
        }
    }

    private fun List<TocListItem>.keys(): List<String> = map { it.key }

    private fun volume(index: Int, title: String): BookChapter {
        return BookChapter(
            url = "volume-$index",
            title = title,
            isVolume = true,
            bookUrl = "book",
            index = index,
        )
    }

    private fun chapter(index: Int, title: String = "Chapter $index"): BookChapter {
        return BookChapter(
            url = "chapter-$index",
            title = title,
            bookUrl = "book",
            index = index,
        )
    }
}
