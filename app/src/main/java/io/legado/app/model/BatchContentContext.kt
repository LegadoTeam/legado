package io.legado.app.model

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.NetworkUtils

/**
 * 批量正文下载上下文。
 *
 * 书源在 contentBatch 规则(或 JS 源的 getContentBatch 函数)里拿到本批章节数组后,
 * 每处理完一章就调用 java.cacheContent(url, content) 主动回存,由本类负责把 url
 * 映射回 BookChapter 并记录已保存的章节。
 *
 * JS 侧可能在多个线程/协程里并发回调,所有可变状态都在 lock 下访问。
 */
class BatchContentContext(
    val bookSource: BookSource,
    val book: Book,
    val chapters: List<BookChapter>
) {

    private val lock = Any()

    /** 已经通过 cacheContent 回存的章节,key 为 chapter.url */
    private val savedChapterUrls = linkedSetOf<String>()

    /**
     * url -> 章节 的查找表。同时登记原始 url 和绝对 url,
     * 书源无论回传目录里的相对地址还是实际请求的绝对地址都能匹配上。
     */
    private val chapterIndex: Map<String, BookChapter> = buildMap {
        chapters.forEach { chapter ->
            put(chapter.url, chapter)
            val absoluteUrl = runCatching { chapter.getAbsoluteURL() }.getOrNull()
            if (!absoluteUrl.isNullOrBlank() && !containsKey(absoluteUrl)) {
                put(absoluteUrl, chapter)
            }
        }
    }

    /**
     * 按 url 查找本批次内的章节,找不到返回 null。
     * 先按登记的原始/绝对 url 直接命中,再退化到规范化后比较,
     * 兼容书源对 url 做过转义或补全的情况。
     */
    fun findChapter(url: String): BookChapter? {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) return null
        chapterIndex[trimmedUrl]?.let { return it }
        val normalizedUrl = normalize(trimmedUrl) ?: return null
        return chapters.firstOrNull { chapter ->
            normalize(chapter.url) == normalizedUrl ||
                normalize(runCatching { chapter.getAbsoluteURL() }.getOrNull()) == normalizedUrl
        }
    }

    private fun normalize(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            NetworkUtils.getAbsoluteURL(book.tocUrl.ifBlank { bookSource.bookSourceUrl }, url)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
    }

    fun markSaved(chapter: BookChapter) {
        synchronized(lock) {
            savedChapterUrls.add(chapter.url)
        }
    }

    fun isSaved(chapter: BookChapter): Boolean = synchronized(lock) {
        savedChapterUrls.contains(chapter.url)
    }

    fun savedCount(): Int = synchronized(lock) { savedChapterUrls.size }

    /** 本批次中书源没有回存的章节,交由调用方按普通流程重试 */
    fun missingChapters(): List<BookChapter> = synchronized(lock) {
        chapters.filterNot { savedChapterUrls.contains(it.url) }
    }
}
