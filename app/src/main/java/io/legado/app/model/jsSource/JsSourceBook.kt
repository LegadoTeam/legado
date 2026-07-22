package io.legado.app.model.jsSource

import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.ContentEmptyException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.exception.TocEmptyException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.addType
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.removeAllBookType
import io.legado.app.help.source.getBookType
import io.legado.app.model.Debug
import io.legado.app.model.webBook.BookChapterList
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

object JsSourceBook {

    suspend fun searchAwait(
        source: BookSource,
        key: String,
        page: Int? = 1,
        filter: ((name: String, author: String, kind: String?) -> Boolean)? = null,
    ): ArrayList<SearchBook> {
        val engine = JsSourceEngine(source, coroutineContext)
        val json = engine.callFunction("search", listOf("key" to key, "page" to (page ?: 1)))
        Debug.log(source.bookSourceUrl, json.orEmpty(), state = 10)
        val books = JsSourceMarshaller.parseSearchBooks(json, source)
        if (filter != null) {
            books.removeAll { !filter(it.name, it.author, it.kind) }
        }
        Debug.log(source.bookSourceUrl, "◇JS源搜索完成,共${books.size}条")
        logSummary(source.bookSourceUrl) { JsSourceDebugFormatter.bookList(books) }
        return books
    }

    suspend fun exploreAwait(
        source: BookSource,
        url: String,
        page: Int? = 1,
    ): ArrayList<SearchBook> {
        val engine = JsSourceEngine(source, coroutineContext)
        val json = engine.callFunction("explore", listOf("url" to url, "page" to (page ?: 1)))
        Debug.log(source.bookSourceUrl, json.orEmpty(), state = 10)
        val books = JsSourceMarshaller.parseSearchBooks(json, source)
        Debug.log(source.bookSourceUrl, "◇JS源发现完成,共${books.size}条")
        logSummary(source.bookSourceUrl) { JsSourceDebugFormatter.bookList(books) }
        return books
    }

    suspend fun getBookInfoAwait(
        source: BookSource,
        book: Book,
        canReName: Boolean,
    ): Book {
        book.removeAllBookType()
        book.addType(source.getBookType())
        val engine = JsSourceEngine(source, coroutineContext)
        val json = engine.callFunctionIfExists("getBookInfo", listOf("book" to book))
        if (json == null) {
            logSummary(source.bookSourceUrl) {
                listOf("≡getBookInfo 未定义或无返回,沿用搜索阶段字段")
            }
        }
        Debug.log(source.bookSourceUrl, json.orEmpty(), state = 20)
        JsSourceMarshaller.mergeBookInfo(book, json, source, canReName)
        if (source.bookSourceType == BookSourceType.file) {
            book.addType(source.getBookType())
        }
        if (!book.isWebFile && book.tocUrl.isBlank()) {
            book.tocUrl = book.bookUrl
        }
        logSummary(source.bookSourceUrl) { JsSourceDebugFormatter.bookInfo(book) }
        if (book.isWebFile && book.downloadUrls.isNullOrEmpty()) {
            throw NoStackTraceException("下载链接为空")
        }
        return book
    }

    suspend fun getChapterListAwait(
        source: BookSource,
        book: Book,
    ): Result<List<BookChapter>> {
        book.removeAllBookType()
        book.addType(source.getBookType())
        val context = coroutineContext
        return runCatching {
            val engine = JsSourceEngine(source, context)
            val json = engine.callFunction("getChapters", listOf("book" to book))
            Debug.log(source.bookSourceUrl, json.orEmpty(), state = 30)
            val chapters = JsSourceMarshaller.parseChapters(json, book, source)
            logSummary(source.bookSourceUrl) { JsSourceDebugFormatter.chapterList(chapters) }
            if (chapters.isEmpty()) {
                throw TocEmptyException("JS源目录为空")
            }
            BookChapterList.updateBookTocInfo(book, chapters)
            Debug.log(source.bookSourceUrl, "◇JS源目录完成,共${chapters.size}章")
            chapters
        }.onFailure {
            context.ensureActive()
        }
    }

    suspend fun getContentAwait(
        source: BookSource,
        book: Book,
        chapter: BookChapter,
        nextChapterUrl: String? = null,
        needSave: Boolean = true,
    ): String {
        if (chapter.isVolume && chapter.url.startsWith(chapter.title)) {
            Debug.log(source.bookSourceUrl, "⇒一级目录正文不解析")
            return chapter.tag.orEmpty()
        }
        val engine = JsSourceEngine(source, coroutineContext)
        val content = engine.callFunction(
            "getContent",
            listOf(
                "chapter" to chapter,
                "book" to book,
                "nextChapterUrl" to nextChapterUrl,
            ),
        )
        if (content.isNullOrBlank()) {
            throw ContentEmptyException("JS源正文为空")
        }
        Debug.log(source.bookSourceUrl, content, state = 40)
        logSummary(source.bookSourceUrl) { JsSourceDebugFormatter.content(chapter, content) }
        if (needSave) {
            BookHelp.saveContent(source, book, chapter, content)
        }
        return content
    }

    private inline fun logSummary(sourceUrl: String, lines: () -> List<String>) {
        if (!Debug.isDebugging(sourceUrl)) return
        lines().forEach { Debug.log(sourceUrl, it) }
    }
}
