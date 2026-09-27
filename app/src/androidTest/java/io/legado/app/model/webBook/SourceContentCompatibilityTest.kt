package io.legado.app.model.webBook

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.model.analyzeRule.AnalyzeByXPath
import io.legado.app.utils.GSON
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceContentCompatibilityTest {
    private val sources get() = InstrumentationRegistry.getInstrumentation().context.assets
        .open("issue1180-content-rules.json").bufferedReader().use {
            GSON.fromJson(it, Array<BookSource>::class.java)
        }
    private val html = """
        <div class="chapter_content">您现在阅读的是<a href="https://www.303wx.com">303文学<a/>www.303wx.com提供的《示例》<br>正文第一段<br>正文第二段<br>【请收藏 303文学 303wx.com】</div>
    """.trimIndent()

    @Test
    fun originalTextNodeAndReplacementRulesRetainChapterContent() = runBlocking {
        val result = analyze(sources[0], html)
        assertEquals(listOf("正文第一段", "正文第二段"), result.lines().map { it.trim() }.filter { it.isNotBlank() })
        val xpath = AnalyzeByXPath(html).getString("//div[@class='chapter_content']/text()")!!
        assertTrue(xpath.contains("正文第一段"))
        assertTrue(xpath.contains("正文第二段"))
        assertFalse(xpath.contains("303文学\n"))
    }

    @Test
    fun originalJavascriptBase64AndBookNameReplacementRulesRetainContent() = runBlocking {
        val encoded = Base64.encodeToString("<p>正文第一段</p><p>正文第二段</p><p>喜欢测试书籍</p>".toByteArray(), Base64.NO_WRAP)
        val result = analyze(sources[1], "<script>var content='$encoded';</script>")
        assertTrue(result.contains("正文第一段"))
        assertTrue(result.contains("正文第二段"))
        assertFalse(result.contains("喜欢测试书籍"))
    }

    @Test
    fun directoryDisplayReversalDoesNotChangeSourceRefreshOrderOrChapterIdentity() = runBlocking {
        val baseUrl = "https://toc-reverse.invalid/"
        val source = BookSource(bookSourceUrl = baseUrl, ruleToc = TocRule(
            chapterList = "tag.a", chapterName = "text", chapterUrl = "href"))
        val body = (1..5).joinToString("") { "<a href='chapter-$it'>Chapter $it</a>" }
        for (sourceReversed in listOf(false, true)) {
            val book = Book(bookUrl = "${baseUrl}book-$sourceReversed", name = "Order fixture", origin = baseUrl)
                .apply { setReverseToc(sourceReversed) }
            suspend fun identities() = BookChapterList.analyzeChapterList(source, book, baseUrl, baseUrl, body)
                .map { listOf(it.index, it.url, it.title) }
            val original = identities()
            assertEquals(5, original.size)
            for (displayReversed in listOf(true, false)) {
                book.setReverseTocDisplay(displayReversed)
                assertEquals("Display changes must not reorder a later source refresh", original, identities())
                assertEquals(sourceReversed, book.getReverseToc())
            }
        }
    }

    private suspend fun analyze(source: BookSource, body: String): String {
        source.bookSourceUrl = "https://source-compatibility.invalid/"
        val book = Book(bookUrl = "https://source-compatibility.invalid/book", name = "测试书籍", origin = source.bookSourceUrl)
        val url = "https://source-compatibility.invalid/chapter"
        return BookContent.analyzeContent(source, book,
            BookChapter(bookUrl = book.bookUrl, url = url, title = "第一章"), url, url, body,
            "https://source-compatibility.invalid/next-chapter", needSave = false)
    }
}
