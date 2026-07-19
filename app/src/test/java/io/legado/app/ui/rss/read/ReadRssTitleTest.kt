package io.legado.app.ui.rss.read

import io.legado.app.data.entities.RssArticle
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadRssTitleTest {

    @Test
    fun `intent title takes precedence`() {
        assertEquals(
            "Article title",
            resolveRssReadTitle(
                intentTitle = "Article title",
                sourceName = "Source name",
                origin = "https://example.com/feed",
            ),
        )
    }

    @Test
    fun `missing intent title falls back to source name`() {
        assertEquals(
            "Source name",
            resolveRssReadTitle(
                intentTitle = null,
                sourceName = "Source name",
                origin = "https://example.com/feed",
            ),
        )
    }

    @Test
    fun `missing source falls back to origin`() {
        assertEquals(
            "https://example.com/feed",
            resolveRssReadTitle(
                intentTitle = null,
                sourceName = null,
                origin = "https://example.com/feed",
            ),
        )
    }

    @Test
    fun `cached article content is used before source rules`() {
        val article = RssArticle(
            origin = "https://example.com/feed",
            title = "Title",
            order = 1L,
            link = "https://example.com/article",
            description = "Cached content",
        )

        assertEquals(
            RssReadLoadTarget.CachedContent("Cached content"),
            resolveRssReadLoadTarget(
                article = article,
                historyLink = "https://example.com/history",
                historyOrigin = "https://example.com/feed",
                ruleContent = "article rule",
            ),
        )
    }

    @Test
    fun `article without cached content uses source rule`() {
        val article = RssArticle(
            origin = "https://example.com/feed",
            title = "Title",
            order = 1L,
            link = "https://example.com/article",
        )

        assertEquals(
            RssReadLoadTarget.RuleContent(article, "article rule"),
            resolveRssReadLoadTarget(
                article = article,
                historyLink = "https://example.com/history",
                historyOrigin = "https://example.com/feed",
                ruleContent = "article rule",
            ),
        )
    }

    @Test
    fun `missing cached article loads the history link directly`() {
        assertEquals(
            RssReadLoadTarget.Url(
                url = "https://example.com/history",
                baseUrl = "https://example.com/feed",
            ),
            resolveRssReadLoadTarget(
                article = null,
                historyLink = "https://example.com/history",
                historyOrigin = "https://example.com/feed",
                ruleContent = "article rule",
            ),
        )
    }

    @Test
    fun `article without cached content or source rule loads its url`() {
        val article = RssArticle(
            origin = "https://example.com/feed",
            title = "Title",
            order = 1L,
            link = "https://example.com/article",
        )

        assertEquals(
            RssReadLoadTarget.Url(
                url = "https://example.com/article",
                baseUrl = "https://example.com/feed",
            ),
            resolveRssReadLoadTarget(
                article = article,
                historyLink = "https://example.com/history",
                historyOrigin = "https://example.com/feed",
                ruleContent = null,
            ),
        )
    }
}
