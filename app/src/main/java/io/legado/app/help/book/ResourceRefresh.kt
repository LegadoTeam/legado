package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File

/** The reader and shelf use the same staged body/image/metadata transaction. */
internal suspend fun refreshBookResources(
    source: BookSource,
    book: Book,
    chapters: List<BookChapter>,
    oldImages: Collection<String> = emptyList(),
    generation: Long = ResourceThemeGeneration.current(),
    onPublished: (List<PendingResourceChapter>) -> Unit = {},
    canPublish: () -> Boolean = { true },
): List<PendingResourceChapter> {
    val tokens = chapters.associate { it.index to BookHelp.contentSaveToken(book, it) }
    val staging = BookHelp.resourceStagingDir(book)
    try {
        val images = linkedSetOf<String>()
        val prepared = chapters.map { original ->
            currentCoroutineContext().ensureActive()
            val chapter = original.copy().apply { deferUpdates = true }
            val content = WebBook.getContentAwait(source, book, chapter, needSave = false)
            if (content.isBlank()) throw NoStackTraceException("刷新正文为空：${chapter.title}")
            chapter.imgUrl?.takeIf { it.isNotBlank() }?.let(images::add)
            BookHelp.flowImages(chapter, content).collect { images.add(it) }
            val (title, processed) = ReadBook.processChapterContent(book, chapter, content)
            BookHelp.flowImages(chapter, title + "\n" + processed.textList.joinToString("\n"))
                .collect { images.add(it) }
            PendingResourceChapter(chapter, tokens.getValue(chapter.index), original.getFileName(),
                content, File(staging, "chapter-${chapter.index}").apply { writeText(content) })
        }
        val imageTokens = images.associateWith { BookHelp.imageSaveVersion(book, it) }
        val preparedImages = images.withIndex().associate { (index, src) ->
            currentCoroutineContext().ensureActive()
            val bytes = BookHelp.fetchImage(source, book, src)
            if (bytes == null || !BookHelp.checkImage(bytes)) throw NoStackTraceException("刷新图片失败：$src")
            src to File(staging, "image-$index").apply { writeBytes(bytes) }
        }
        return withContext(Main) {
            currentCoroutineContext().ensureActive()
            check(canPublish()) { "书籍或资源请求已变化，请重新刷新" }
            var published = emptyList<PendingResourceChapter>()
            ResourceThemeGeneration.publish(generation) {
                ImageProvider.replaceResources(book, oldImages + images) {
                    published = BookHelp.commitResources(book, prepared, preparedImages, imageTokens, generation)
                }
            }
            // Cancellation must not split accepted files from reader/ticket publication.
            // Release the generation lock first: download ownership can acquire it in the opposite order.
            onPublished(published)
            published
        }
    } finally {
        staging.deleteRecursively()
    }
}
