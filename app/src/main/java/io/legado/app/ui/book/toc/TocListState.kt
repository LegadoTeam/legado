package io.legado.app.ui.book.toc

import io.legado.app.data.entities.BookChapter
import io.legado.app.model.localBook.EpubTocNode

class TocListState {
    private data class Node(
        val chapter: BookChapter,
        val readingChapter: BookChapter? = chapter,
        val depth: Int = 0,
        val parentIndex: Int? = null,
    )
    private data class VolumeGroup(val volume: BookChapter?, val chapters: List<BookChapter>)

    private var fullChapters: List<BookChapter> = emptyList()
    private var nodes: List<Node> = emptyList()
    private var byIndex: Map<Int, Node> = emptyMap()
    private var descendantCounts: Map<Int, Int> = emptyMap()
    private val collapsedVolumeIndexes = mutableSetOf<Int>()
    private var reverseOrder = false
    private var reverseDisplay = false

    var visibleItems: List<TocListItem> = emptyList()
        private set

    fun hasFullChapters(): Boolean = fullChapters.isNotEmpty()

    fun clear() {
        fullChapters = emptyList()
        nodes = emptyList()
        byIndex = emptyMap()
        descendantCounts = emptyMap()
        collapsedVolumeIndexes.clear()
        reverseOrder = false
        reverseDisplay = false
        visibleItems = emptyList()
    }

    fun setFullChapters(
        chapters: List<BookChapter>,
        reverseOrder: Boolean,
        resetCollapse: Boolean = false,
        defaultExpanded: Boolean = true,
        currentChapterIndex: Int? = null,
        epubToc: List<EpubTocNode>? = null,
        reverseDisplay: Boolean = false,
    ) {
        val directionChanged = this.reverseOrder != reverseOrder || this.reverseDisplay != reverseDisplay
        val previousParents = descendantCounts.keys
        this.reverseOrder = reverseOrder
        this.reverseDisplay = reverseDisplay
        fullChapters = chapters
        nodes = if (!epubToc.isNullOrEmpty()) {
            buildEpubNodes(chapters, epubToc, reverseOrder)
        } else {
            val groups = buildGroups(chapters, reverseOrder)
            (if (reverseDisplay) groups.asReversed() else groups).flatMap { group ->
                buildList {
                    group.volume?.let { add(Node(it)) }
                    (if (reverseDisplay) group.chapters.asReversed() else group.chapters).forEach {
                        add(Node(it, depth = if (group.volume == null) 0 else 1,
                            parentIndex = group.volume?.index))
                    }
                }
            }
        }
        byIndex = nodes.associateBy { it.chapter.index }
        descendantCounts = mutableMapOf<Int, Int>().apply {
            nodes.asReversed().forEach { node ->
                node.parentIndex?.let { parent ->
                    this[parent] = (this[parent] ?: 0) + 1 + (this[node.chapter.index] ?: 0)
                }
            }
        }
        val currentPath = currentChapterIndex?.let(::currentPath).orEmpty()
        if (resetCollapse || directionChanged) {
            collapsedVolumeIndexes.clear()
            if (!defaultExpanded) collapsedVolumeIndexes.addAll(descendantCounts.keys - currentPath)
        } else {
            collapsedVolumeIndexes.retainAll(descendantCounts.keys)
            collapsedVolumeIndexes.removeAll(currentPath)
            if (!defaultExpanded) {
                collapsedVolumeIndexes.addAll(descendantCounts.keys - previousParents - currentPath)
            }
        }
    }

    fun showNormal(currentChapterIndex: Int): List<TocListItem> {
        val hidden = hashSetOf<Int>()
        val currentPath = currentPath(currentChapterIndex)
        visibleItems = nodes.mapNotNull { node ->
            val index = node.chapter.index
            if (node.parentIndex in hidden) {
                hidden.add(index)
                return@mapNotNull null
            }
            val collapsed = index in collapsedVolumeIndexes
            if (collapsed) hidden.add(index)
            item(node, collapsed, currentPath)
        }
        return visibleItems
    }

    fun searchIndexes(query: String): List<Int> = nodes.filter {
        it.chapter.title.contains(query, ignoreCase = true)
    }.map { it.chapter.index }

    fun showSearch(searchResultIndexes: Collection<Int>, currentChapterIndex: Int): List<TocListItem> {
        val matched = searchResultIndexes.toHashSet()
        val included = hashSetOf<Int>()
        matched.forEach { included.addAll(ancestorPath(it)) }
        val matchCounts = mutableMapOf<Int, Int>()
        nodes.asReversed().forEach { node ->
            node.parentIndex?.let { parent ->
                matchCounts[parent] = (matchCounts[parent] ?: 0) +
                        (if (node.chapter.index in matched) 1 else 0) + (matchCounts[node.chapter.index] ?: 0)
            }
        }
        val currentPath = currentPath(currentChapterIndex)
        visibleItems = nodes.filter { it.chapter.index in included }.map { node ->
            item(node, false, currentPath, matchCounts[node.chapter.index] ?: 0,
                node.chapter.index in matched)
        }
        return visibleItems
    }

    fun toggleVolume(volumeIndex: Int): Boolean {
        if (volumeIndex !in descendantCounts) return false
        if (!collapsedVolumeIndexes.add(volumeIndex)) collapsedVolumeIndexes.remove(volumeIndex)
        return true
    }

    fun expandVolumeContainingChapter(chapterIndex: Int): Boolean =
        collapsedVolumeIndexes.removeAll(currentPath(chapterIndex))

    fun isVolumeCollapsed(volumeIndex: Int): Boolean = volumeIndex in collapsedVolumeIndexes

    fun parentVolumeIndexOf(chapterIndex: Int): Int? =
        (byIndex[chapterIndex] ?: nodes.firstOrNull { it.readingChapter?.index == chapterIndex })?.parentIndex

    fun isDescendantOf(itemIndex: Int, ancestorIndex: Int): Boolean =
        ancestorPath(itemIndex).drop(1).contains(ancestorIndex)

    fun findVisiblePositionByChapterIndex(chapterIndex: Int): Int = visibleItems.indexOfFirst {
        it is TocListItem.Chapter && it.readingChapter?.index == chapterIndex
    }

    fun findVisiblePositionByVolumeIndex(volumeIndex: Int): Int = visibleItems.indexOfFirst {
        it is TocListItem.Volume && it.chapter.index == volumeIndex
    }

    fun findFallbackVisiblePositionForChapterIndex(chapterIndex: Int): Int {
        val direct = visibleItems.indexOfFirst { it.readingChapter?.index == chapterIndex }
        if (direct >= 0) return direct
        val node = nodes.firstOrNull { it.readingChapter?.index == chapterIndex } ?: return -1
        for (index in ancestorPath(node.chapter.index)) {
            val position = visibleItems.indexOfFirst { it.chapter.index == index }
            if (position >= 0) return position
        }
        return -1
    }

    fun findVisiblePositionByItemKey(key: String): Int = visibleItems.indexOfFirst { it.key == key }

    private fun currentPath(chapterIndex: Int): Set<Int> = nodes
        .filter { it.readingChapter?.index == chapterIndex }
        .flatMapTo(hashSetOf()) { ancestorPath(it.chapter.index) }

    private fun ancestorPath(index: Int): List<Int> = buildList {
        var node = byIndex[index]
        while (node != null) {
            add(node.chapter.index)
            node = byIndex[node.parentIndex]
        }
    }

    private fun item(node: Node, collapsed: Boolean, currentPath: Set<Int>,
                     matchedCount: Int? = null, matchedSelf: Boolean = false): TocListItem =
        if (node.chapter.isVolume) TocListItem.Volume(
            chapter = node.chapter, depth = node.depth, collapsed = collapsed,
            chapterCount = descendantCounts[node.chapter.index] ?: 0,
            matchedCount = matchedCount, matchedSelf = matchedSelf,
            containsCurrentChapter = node.chapter.index in currentPath,
            readingChapter = node.readingChapter,
        ) else TocListItem.Chapter(
            chapter = node.chapter, depth = node.depth, parentVolumeIndex = node.parentIndex,
            readingChapter = node.readingChapter,
        )

    private fun buildEpubNodes(chapters: List<BookChapter>, toc: List<EpubTocNode>, reverse: Boolean): List<Node> {
        val chaptersByUrl = chapters.associateBy { it.url }
        val referencedUrls = toc.mapNotNullTo(hashSetOf()) { it.href }
        val parents = toc.mapNotNullTo(hashSetOf()) { it.parentId }
        val contentNodes = toc.map { entry ->
            val reading = chaptersByUrl[entry.href]
            // Navigation-only indexes never enter Room, reading progress, or bookmarks.
            val display = (reading ?: BookChapter()).copy(
                index = -1 - entry.id, title = entry.title, url = entry.href ?: "epub-toc://${entry.id}",
                bookUrl = reading?.bookUrl ?: chapters.firstOrNull()?.bookUrl.orEmpty(),
                isVolume = entry.id in parents || reading == null,
            )
            Node(display, reading, entry.depth, entry.parentId?.let { -1 - it })
        }
        val all = chapters.filter { it.url !in referencedUrls }.map { Node(it.copy(isVolume = false), it) } + contentNodes
        if (!reverse) return all
        val children = all.groupBy { it.parentIndex }
        val pending = ArrayDeque<Node>()
        children[null].orEmpty().forEach(pending::addLast)
        return buildList {
            while (pending.isNotEmpty()) {
                val node = pending.removeLast()
                add(node)
                children[node.chapter.index].orEmpty().forEach(pending::addLast)
            }
        }
    }

    private fun buildGroups(chapters: List<BookChapter>, reverseOrder: Boolean): List<VolumeGroup> {
        val result = mutableListOf<VolumeGroup>()
        var volume: BookChapter? = null
        var children = mutableListOf<BookChapter>()
        for (chapter in chapters) {
            if (chapter.isVolume) {
                if (reverseOrder) {
                    result.add(VolumeGroup(chapter, children))
                } else {
                    if (volume != null || children.isNotEmpty()) result.add(VolumeGroup(volume, children))
                    volume = chapter
                }
                children = mutableListOf()
            } else children.add(chapter)
        }
        if ((!reverseOrder && volume != null) || children.isNotEmpty()) {
            result.add(VolumeGroup(if (reverseOrder) null else volume, children))
        }
        return result
    }
}
