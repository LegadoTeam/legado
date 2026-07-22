package io.legado.app.model.jsSource

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.SearchBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsSourceDebugFormatterTest {

    @Test
    fun `book list includes parsed first item and total`() {
        val lines = JsSourceDebugFormatter.bookList(
            listOf(
                SearchBook(
                    name = "第一本",
                    author = "作者",
                    bookUrl = "https://example.com/1",
                ),
                SearchBook(name = "第二本", bookUrl = "https://example.com/2"),
            )
        )

        assertTrue(lines.contains("└列表大小:2"))
        assertTrue(lines.contains("└第一本"))
        assertTrue(lines.contains("└作者"))
        assertTrue(lines.contains("◇书籍总数:2"))
        assertFalse(lines.contains("└第二本"))
    }

    @Test
    fun `book info contains merged fields`() {
        val book = Book(bookUrl = "https://example.com/book", name = "书名", author = "作者")
        book.tocUrl = "https://example.com/toc"

        val lines = JsSourceDebugFormatter.bookInfo(book)

        assertTrue(lines.contains("└书名"))
        assertTrue(lines.contains("└作者"))
        assertTrue(lines.contains("└https://example.com/toc"))
    }

    @Test
    fun `file book info contains download urls instead of toc`() {
        val book = Book(bookUrl = "https://example.com/book", type = BookType.webFile)
        book.downloadUrls = listOf("https://example.com/1.epub")

        val lines = JsSourceDebugFormatter.bookInfo(book)

        assertTrue(lines.contains("┌获取文件下载链接"))
        assertTrue(lines.contains("└https://example.com/1.epub"))
        assertFalse(lines.contains("┌获取目录链接"))
    }

    @Test
    fun `chapter list includes first and last chapter`() {
        val lines = JsSourceDebugFormatter.chapterList(
            listOf(
                BookChapter(title = "第一章", url = "https://example.com/1"),
                BookChapter(title = "第二章", url = "https://example.com/2", isVip = true),
            )
        )

        assertTrue(lines.contains("◇目录总数:2"))
        assertTrue(lines.contains("≡首章信息"))
        assertTrue(lines.contains("≡末章信息"))
        assertTrue(lines.contains("◇是否VIP:true"))
    }

    @Test
    fun `empty chapter list still reports zero total`() {
        val lines = JsSourceDebugFormatter.chapterList(emptyList())

        assertTrue(lines.contains("└列表大小:0"))
        assertTrue(lines.contains("◇目录总数:0"))
        assertFalse(lines.any { it.startsWith("≡首章") })
    }

    @Test
    fun `single chapter is not duplicated as last chapter`() {
        val lines = JsSourceDebugFormatter.chapterList(
            listOf(BookChapter(title = "唯一章节", url = "https://example.com/1"))
        )

        assertEquals(1, lines.count { it == "≡首章信息" })
        assertFalse(lines.contains("≡末章信息"))
    }

    @Test
    fun `sensitive values are redacted from summaries`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature"
        val basicCredentials = "dXNlcjpwYXNzd29yZA=="
        val parameterCredentials = "YW5vdGhlcjpwYXNzd29yZA=="
        val lines = JsSourceDebugFormatter.bookList(
            listOf(
                SearchBook(
                    name = "书名",
                    bookUrl = "https://user:pass@example.com/$jwt?token=secret&keyword=visible",
                    intro = "Cookie: session=cookie-secret\n" +
                        "Authorization: Basic $basicCredentials\n" +
                        "Authorization=Basic $parameterCredentials",
                )
            )
        )

        assertTrue(lines.any { it.contains("[redacted]") })
        assertTrue(lines.any { it.contains("keyword=visible") })
        assertFalse(lines.any { it.contains("user") })
        assertFalse(lines.any { it.contains("pass") })
        assertFalse(lines.any { it.contains(jwt) })
        assertFalse(lines.any { it.contains("secret") })
        assertFalse(lines.any { it.contains(basicCredentials) })
        assertFalse(lines.any { it.contains(parameterCredentials) })
    }

    @Test
    fun `long fields are truncated`() {
        val longIntro = "a".repeat(600)
        val lines = JsSourceDebugFormatter.bookList(
            listOf(SearchBook(name = "书名", bookUrl = "https://example.com", intro = longIntro))
        )
        val introLine = lines.first { it.startsWith("└aaaa") }

        assertTrue(introLine.endsWith("...(已截断)"))
        assertFalse(introLine.contains(longIntro))
    }

    @Test
    fun `content summary does not duplicate full content`() {
        val secretContent = "不应写入普通日志的正文"
        val lines = JsSourceDebugFormatter.content(
            BookChapter(title = "测试章节"),
            secretContent,
        )

        assertTrue(lines.contains("└测试章节"))
        assertTrue(lines.contains("└正文长度:${secretContent.length}"))
        assertFalse(lines.any { it.contains(secretContent) })
    }
}
