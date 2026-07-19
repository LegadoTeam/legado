package io.legado.app.service

import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.TocEmptyException

internal data class CheckSourceChapterSelection(
    val chapter: BookChapter,
    val nextChapterUrl: String,
)

internal fun selectCheckSourceChapter(
    chapters: List<BookChapter>,
    emptyMessage: String,
): CheckSourceChapterSelection {
    val readableChapters = chapters.asSequence()
        .filterNot { it.isVolume && it.url.startsWith(it.title) }
        .take(2)
        .toList()
    val chapter = readableChapters.firstOrNull()
        ?: throw TocEmptyException(emptyMessage)
    return CheckSourceChapterSelection(
        chapter = chapter,
        nextChapterUrl = readableChapters.getOrNull(1)?.url ?: chapter.url,
    )
}
