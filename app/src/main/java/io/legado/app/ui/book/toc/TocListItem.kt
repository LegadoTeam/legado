package io.legado.app.ui.book.toc

import io.legado.app.data.entities.BookChapter

sealed class TocListItem {
    abstract val key: String
    abstract val chapter: BookChapter
    abstract val depth: Int

    data class Volume(
        override val chapter: BookChapter,
        override val depth: Int,
        val collapsed: Boolean,
        val chapterCount: Int,
        val matchedCount: Int? = null,
        val matchedSelf: Boolean = false,
        val containsCurrentChapter: Boolean = false,
    ) : TocListItem() {
        override val key: String = "volume:${chapter.index}"
        val canToggle: Boolean
            get() = chapterCount > 0 && matchedCount == null
    }

    data class Chapter(
        override val chapter: BookChapter,
        override val depth: Int,
        val parentVolumeIndex: Int? = null,
    ) : TocListItem() {
        override val key: String = "chapter:${chapter.index}"
    }
}
