package io.legado.app.model.rss

import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.help.http.StrResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RssContentPaginationTest {

    @Test
    fun `content page returns content and absolute next page url`() = runBlocking {
        val article = RssArticle(
            origin = "https://feed.example/rss",
            title = "Article",
            link = "https://site.example/page/1",
        )
        val source = RssSource(
            sourceUrl = article.origin,
            nextContentUrl = ".next@href",
        )

        val result = Rss.analyzeContentPage(
            rssArticle = article,
            rssSource = source,
            ruleContent = "article@text",
            baseUrl = article.link,
            response = StrResponse(
                article.link,
                """<article>first page</article><a class="next" href="/page/2">next</a>""",
            ),
        )

        assertEquals("first page", result.first)
        assertEquals(listOf("https://site.example/page/2"), result.second)
    }

    @Test
    fun `follow-up page can skip discovering more urls`() = runBlocking {
        val article = RssArticle(
            origin = "https://feed.example/rss",
            title = "Article",
            link = "https://site.example/page/1",
        )
        val source = RssSource(
            sourceUrl = article.origin,
            nextContentUrl = ".next@href",
        )

        val result = Rss.analyzeContentPage(
            rssArticle = article,
            rssSource = source,
            ruleContent = "article@text",
            baseUrl = article.link,
            response = StrResponse(
                article.link,
                """<article>first page</article><a class="next" href="/page/2">next</a>""",
            ),
            getNextPageUrl = false,
        )

        assertEquals("first page", result.first)
        assertTrue(result.second.isEmpty())
    }

    @Test
    fun `image and video sources keep their single media url contract`() = runBlocking {
        val article = RssArticle(
            origin = "https://feed.example/rss",
            title = "Article",
            link = "https://site.example/page/1",
        )
        listOf(1, 2).forEach { type ->
            val result = Rss.analyzeContentPage(
                rssArticle = article,
                rssSource = RssSource(
                    sourceUrl = article.origin,
                    type = type,
                    nextContentUrl = ".next@href",
                ),
                ruleContent = "article@text",
                baseUrl = article.link,
                response = StrResponse(
                    article.link,
                    """<article>https://media.example/item</article>""" +
                        """<a class="next" href="/page/2">next</a>""",
                ),
            )

            assertEquals("https://media.example/item", result.first)
            assertTrue(result.second.isEmpty())
        }
    }

    @Test
    fun `historical media articles do not paginate after the source type changes`() = runBlocking {
        val article = RssArticle(
            origin = "https://feed.example/rss",
            title = "Article",
            link = "https://site.example/page/1",
            type = 1,
        )
        val result = Rss.analyzeContentPage(
            rssArticle = article,
            rssSource = RssSource(
                sourceUrl = article.origin,
                type = 0,
                nextContentUrl = ".next@href",
            ),
            ruleContent = "article@text",
            baseUrl = article.link,
            response = StrResponse(
                article.link,
                """<article>https://media.example/item</article>""" +
                    """<a class="next" href="/page/2">next</a>""",
            ),
        )

        assertEquals("https://media.example/item", result.first)
        assertTrue(result.second.isEmpty())
    }

    @Test
    fun `single next links are followed in order and stop at a loop`() = runBlocking {
        val article = article()
        val pages = mapOf(
            article.link to page("one", "/page/2"),
            "https://site.example/page/2" to page("two", "/page/3"),
            "https://site.example/page/3" to page("three", "/page/2"),
        )
        val requested = arrayListOf<String>()

        val content = Rss.getContentAwait(article, "article@text", source()) { url, _ ->
            requested.add(url)
            StrResponse(url, requireNotNull(pages[url]))
        }

        assertEquals("one\ntwo\nthree", content)
        assertEquals(
            listOf(article.link, "https://site.example/page/2", "https://site.example/page/3"),
            requested,
        )
    }

    @Test
    fun `multiple first-page links are fetched once and keep rule order`() = runBlocking {
        val article = article()
        val pages = mapOf(
            article.link to """<article>one</article>""" +
                """<a class="next" href="/page/2">two</a>""" +
                """<a class="next" href="/page/3">three</a>""",
            "https://site.example/page/2" to page("two", "/ignored"),
            "https://site.example/page/3" to page("three", "/ignored"),
        )

        val content = Rss.getContentAwait(
            article,
            "article@text",
            source(),
            concurrency = 2,
        ) { url, _ -> StrResponse(url, requireNotNull(pages[url])) }

        assertEquals("one\ntwo\nthree", content)
    }

    @Test
    fun `source comparison includes the content pagination rule`() {
        val original = RssSource(sourceUrl = "https://feed.example/rss")

        assertTrue(original.equal(original.copy(nextContentUrl = "")))
        assertFalse(original.equal(original.copy(nextContentUrl = ".next@href")))
    }

    @Test
    fun `runtime keeps book-source pagination semantics and editor wiring`() {
        val rss = projectFile("src/main/java/io/legado/app/model/rss/Rss.kt")
        val editor = projectFile(
            "src/main/java/io/legado/app/ui/rss/source/edit/RssSourceEditActivity.kt"
        )
        val help = projectFile("src/main/assets/web/help/md/rssRuleHelp.md")

        assertTrue(rss.contains("contentData.second.size == 1"))
        assertTrue(rss.contains("visitedUrls.add(nextUrl)"))
        assertTrue(rss.contains("contentData.second.size > 1"))
        assertTrue(rss.contains("AppConfig.threadCount"))
        assertTrue(rss.contains("mapAsync(concurrency)"))
        assertTrue(rss.contains("contentList.joinToString(\"\\n\")"))
        assertTrue(editor.indexOf("\"ruleContent\"") < editor.indexOf("\"nextContentUrl\""))
        assertTrue(editor.contains("R.string.rule_next_content"))
        assertTrue(editor.contains("ruleComplete(it.value, type = 2)"))
        assertTrue(help.contains("仅用于网页类型"))
        assertTrue(help.contains("一次返回多个 URL 时会并发获取一轮"))
    }

    private fun projectFile(path: String): String {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, "app/$path").readText()
    }

    private fun article() = RssArticle(
        origin = "https://feed.example/rss",
        title = "Article",
        link = "https://site.example/page/1",
    )

    private fun source() = RssSource(
        sourceUrl = "https://feed.example/rss",
        nextContentUrl = ".next@href",
    )

    private fun page(content: String, nextUrl: String) =
        """<article>$content</article><a class="next" href="$nextUrl">next</a>"""
}
