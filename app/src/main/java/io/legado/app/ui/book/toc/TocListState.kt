package io.legado.app.ui.book.toc

import io.legado.app.data.entities.BookChapter

class TocListState {

    private data class VolumeGroup(
        val volume: BookChapter?,
        val chapters: List<BookChapter>,
    )

    private var fullChapters: List<BookChapter> = emptyList()
    private var groups: List<VolumeGroup> = emptyList()
    private var parentVolumeByChapterIndex: Map<Int, Int> = emptyMap()
    private var volumeGroupByIndex: Map<Int, VolumeGroup> = emptyMap()
    private val collapsedVolumeIndexes = mutableSetOf<Int>()
    private var reverseOrder = false

    var visibleItems: List<TocListItem> = emptyList()
        private set

    fun hasFullChapters(): Boolean = fullChapters.isNotEmpty()

    fun clear() {
        fullChapters = emptyList()
        groups = emptyList()
        parentVolumeByChapterIndex = emptyMap()
        volumeGroupByIndex = emptyMap()
        collapsedVolumeIndexes.clear()
        reverseOrder = false
        visibleItems = emptyList()
    }

    fun setFullChapters(
        chapters: List<BookChapter>,
        reverseOrder: Boolean,
        resetCollapse: Boolean = false,
    ) {
        val directionChanged = this.reverseOrder != reverseOrder
        this.reverseOrder = reverseOrder
        fullChapters = chapters
        groups = buildGroups(chapters, reverseOrder)
        rebuildIndexes()
        val collapsibleVolumeIndexes = groups
            .filter { it.volume != null && it.chapters.isNotEmpty() }
            .mapTo(mutableSetOf()) { it.volume!!.index }
        if (resetCollapse || directionChanged) {
            collapsedVolumeIndexes.clear()
        } else {
            collapsedVolumeIndexes.retainAll(collapsibleVolumeIndexes)
        }
    }

    fun showNormal(currentChapterIndex: Int): List<TocListItem> {
        val items = mutableListOf<TocListItem>()
        groups.forEach { group ->
            val volume = group.volume
            if (volume == null) {
                group.chapters.forEach { chapter ->
                    items.add(TocListItem.Chapter(chapter = chapter, depth = 0))
                }
                return@forEach
            }

            val collapsed = collapsedVolumeIndexes.contains(volume.index)
            val volumeItem = volumeItem(
                group = group,
                collapsed = collapsed,
                currentChapterIndex = currentChapterIndex,
            )
            if (collapsed) {
                items.add(volumeItem)
            } else {
                items.add(volumeItem)
                addChapterItems(items, group)
            }
        }
        visibleItems = items
        return items
    }

    fun showSearch(
        searchResultIndexes: Collection<Int>,
        currentChapterIndex: Int,
    ): List<TocListItem> {
        val matchedIndexes = searchResultIndexes.toHashSet()
        val items = mutableListOf<TocListItem>()
        groups.forEach { group ->
            val matchedChapters = group.chapters.filter { it.index in matchedIndexes }
            val volume = group.volume
            if (volume == null) {
                matchedChapters.forEach { chapter ->
                    items.add(TocListItem.Chapter(chapter = chapter, depth = 0))
                }
                return@forEach
            }

            val matchedSelf = volume.index in matchedIndexes
            if (!matchedSelf && matchedChapters.isEmpty()) return@forEach
            val volumeItem = volumeItem(
                group = group,
                collapsed = false,
                currentChapterIndex = currentChapterIndex,
                matchedCount = matchedChapters.size,
                matchedSelf = matchedSelf,
            )
            items.add(volumeItem)
            addChapterItems(items, group, matchedChapters)
        }
        visibleItems = items
        return items
    }

    fun toggleVolume(volumeIndex: Int): Boolean {
        val group = volumeGroupByIndex[volumeIndex] ?: return false
        if (group.chapters.isEmpty()) return false
        if (!collapsedVolumeIndexes.add(volumeIndex)) {
            collapsedVolumeIndexes.remove(volumeIndex)
        }
        return true
    }

    fun expandVolumeContainingChapter(chapterIndex: Int): Boolean {
        val volumeIndex = volumeIndexContaining(chapterIndex) ?: return false
        return collapsedVolumeIndexes.remove(volumeIndex)
    }

    fun isVolumeCollapsed(volumeIndex: Int): Boolean {
        return collapsedVolumeIndexes.contains(volumeIndex)
    }

    fun parentVolumeIndexOf(chapterIndex: Int): Int? {
        return parentVolumeByChapterIndex[chapterIndex]
    }

    fun findVisiblePositionByChapterIndex(chapterIndex: Int): Int {
        return visibleItems.indexOfFirst {
            it is TocListItem.Chapter && it.chapter.index == chapterIndex
        }
    }

    fun findVisiblePositionByVolumeIndex(volumeIndex: Int): Int {
        return visibleItems.indexOfFirst {
            it is TocListItem.Volume && it.chapter.index == volumeIndex
        }
    }

    fun findFallbackVisiblePositionForChapterIndex(chapterIndex: Int): Int {
        val chapterPosition = findVisiblePositionByChapterIndex(chapterIndex)
        if (chapterPosition >= 0) return chapterPosition
        val volumePosition = findVisiblePositionByVolumeIndex(chapterIndex)
        if (volumePosition >= 0) return volumePosition
        val parentVolumeIndex = parentVolumeByChapterIndex[chapterIndex] ?: return -1
        return findVisiblePositionByVolumeIndex(parentVolumeIndex)
    }

    fun findVisiblePositionByItemKey(key: String): Int {
        return visibleItems.indexOfFirst { it.key == key }
    }

    private fun volumeItem(
        group: VolumeGroup,
        collapsed: Boolean,
        currentChapterIndex: Int,
        matchedCount: Int? = null,
        matchedSelf: Boolean = false,
    ): TocListItem.Volume {
        val volume = requireNotNull(group.volume)
        return TocListItem.Volume(
            chapter = volume,
            depth = 0,
            collapsed = collapsed,
            chapterCount = group.chapters.size,
            matchedCount = matchedCount,
            matchedSelf = matchedSelf,
            containsCurrentChapter = volume.index == currentChapterIndex ||
                    parentVolumeByChapterIndex[currentChapterIndex] == volume.index,
        )
    }

    private fun addChapterItems(
        target: MutableList<TocListItem>,
        group: VolumeGroup,
        chapters: List<BookChapter> = group.chapters,
    ) {
        val volumeIndex = group.volume?.index
        chapters.forEach { chapter ->
            target.add(
                TocListItem.Chapter(
                    chapter = chapter,
                    depth = if (volumeIndex == null) 0 else 1,
                    parentVolumeIndex = volumeIndex,
                )
            )
        }
    }

    private fun buildGroups(
        chapters: List<BookChapter>,
        reverseOrder: Boolean,
    ): List<VolumeGroup> {
        return if (reverseOrder) {
            buildReverseGroups(chapters)
        } else {
            buildForwardGroups(chapters)
        }
    }

    private fun buildForwardGroups(chapters: List<BookChapter>): List<VolumeGroup> {
        val result = mutableListOf<VolumeGroup>()
        var currentVolume: BookChapter? = null
        var currentChapters = mutableListOf<BookChapter>()

        fun flush() {
            if (currentVolume != null || currentChapters.isNotEmpty()) {
                result.add(VolumeGroup(currentVolume, currentChapters.toList()))
            }
        }

        chapters.forEach { chapter ->
            if (chapter.isVolume) {
                flush()
                currentVolume = chapter
                currentChapters = mutableListOf()
            } else {
                currentChapters.add(chapter)
            }
        }
        flush()
        return result
    }

    private fun buildReverseGroups(chapters: List<BookChapter>): List<VolumeGroup> {
        val result = mutableListOf<VolumeGroup>()
        var currentChapters = mutableListOf<BookChapter>()
        chapters.forEach { chapter ->
            if (chapter.isVolume) {
                result.add(VolumeGroup(chapter, currentChapters.toList()))
                currentChapters = mutableListOf()
            } else {
                currentChapters.add(chapter)
            }
        }
        if (currentChapters.isNotEmpty()) {
            result.add(VolumeGroup(null, currentChapters.toList()))
        }
        return result
    }

    private fun rebuildIndexes() {
        val parentMap = mutableMapOf<Int, Int>()
        val volumeMap = mutableMapOf<Int, VolumeGroup>()
        groups.forEach { group ->
            val volume = group.volume ?: return@forEach
            volumeMap[volume.index] = group
            group.chapters.forEach { chapter ->
                parentMap[chapter.index] = volume.index
            }
        }
        parentVolumeByChapterIndex = parentMap
        volumeGroupByIndex = volumeMap
    }

    private fun volumeIndexContaining(chapterIndex: Int): Int? {
        return parentVolumeByChapterIndex[chapterIndex]
            ?: chapterIndex.takeIf { volumeGroupByIndex.containsKey(it) }
    }
}
