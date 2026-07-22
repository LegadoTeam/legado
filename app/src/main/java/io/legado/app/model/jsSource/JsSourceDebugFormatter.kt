package io.legado.app.model.jsSource

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.isWebFile
import io.legado.app.help.http.HttpLogSanitizer

internal object JsSourceDebugFormatter {

    private const val MAX_FIELD_LENGTH = 512
    private const val TRUNCATED_SUFFIX = "...(已截断)"

    fun bookList(books: List<SearchBook>): List<String> = buildList {
        add("┌获取书籍列表")
        add("└列表大小:${books.size}")
        books.firstOrNull()?.let { book ->
            addField("书名", book.name)
            addField("作者", book.author)
            addField("分类", book.kind)
            addField("字数", book.wordCount)
            addField("最新章节", book.latestChapterTitle)
            addField("简介", book.intro)
            addField("封面链接", book.coverUrl)
            addField("详情页链接", book.bookUrl)
        }
        add("◇书籍总数:${books.size}")
    }

    fun bookInfo(book: Book): List<String> = buildList {
        addField("书名", book.name)
        addField("作者", book.author)
        addField("分类", book.kind)
        addField("字数", book.wordCount)
        addField("最新章节", book.latestChapterTitle)
        addField("简介", book.intro)
        addField("封面链接", book.coverUrl)
        if (book.isWebFile) {
            addField("文件下载链接", book.downloadUrls?.joinToString("，\n"))
        } else {
            addField("目录链接", book.tocUrl)
        }
    }

    fun chapterList(chapters: List<BookChapter>): List<String> = buildList {
        add("┌获取目录列表")
        add("└列表大小:${chapters.size}")
        add("◇目录总数:${chapters.size}")
        if (chapters.isEmpty()) return@buildList
        addChapter("首章", chapters.first())
        if (chapters.size > 1) {
            addChapter("末章", chapters.last())
        }
    }

    fun content(chapter: BookChapter, content: String): List<String> = listOf(
        "┌获取章节名称",
        "└${formatValue(chapter.title)}",
        "┌获取正文内容",
        "└正文长度:${content.length}",
    )

    private fun MutableList<String>.addField(label: String, value: Any?) {
        add("┌获取$label")
        add("└${formatValue(value)}")
    }

    private fun MutableList<String>.addChapter(label: String, chapter: BookChapter) {
        add("≡${label}信息")
        add("◇章节名称:${formatValue(chapter.title)}")
        add("◇章节链接:${formatValue(chapter.url)}")
        add("◇章节信息:${formatValue(chapter.tag)}")
        add("◇是否VIP:${chapter.isVip}")
        add("◇是否购买:${chapter.isPay}")
    }

    private fun formatValue(value: Any?): String {
        val sanitized = HttpLogSanitizer.redactUrlOrFreeText(value?.toString().orEmpty())
        return if (sanitized.length <= MAX_FIELD_LENGTH) {
            sanitized
        } else {
            sanitized.take(MAX_FIELD_LENGTH) + TRUNCATED_SUFFIX
        }
    }
}
