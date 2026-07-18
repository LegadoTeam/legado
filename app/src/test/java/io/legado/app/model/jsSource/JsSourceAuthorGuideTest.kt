package io.legado.app.model.jsSource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JsSourceAuthorGuideTest {

    private val guide by lazy {
        val document = source("app/src/main/assets/web/help/md/jsHelp.md")
        val startMarker = "<!-- js-source-guide:start -->"
        val endMarker = "<!-- js-source-guide:end -->"
        assertEquals("Guide start marker count", 1, document.countOccurrences(startMarker))
        assertEquals("Guide end marker count", 1, document.countOccurrences(endMarker))
        document.substringAfter(startMarker).substringBefore(endMarker)
    }

    @Test
    fun `documented example remains importable`() {
        val match = Regex(
            """<!-- js-source-example:start -->\s*```js\s*(.*?)\s*```\s*<!-- js-source-example:end -->""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(guide)
        assertNotNull("Missing JavaScript source example markers", match)

        val script = match!!.groupValues[1].trim()
        val source = JsSourceConfig.extract(script)

        assertEquals("https://example.com", source.bookSourceUrl)
        assertEquals("示例 JS 书源", source.bookSourceName)
        assertTrue(source.loginUi.orEmpty().contains("账号"))
        assertTrue(source.exploreUrl.orEmpty().contains("分类"))
        assertEquals(script, source.mainJs)
    }

    @Test
    fun `guide describes current JavaScript source contracts`() {
        val requiredText = listOf(
            "sourceApi.getLoginInfo()",
            "function explore(url, page)",
            "function getContent(chapter, book, nextChapterUrl)",
            "`4` 视频",
            "`loginUi` 非空时必选",
            "`exploreUrl` 非空时必选",
            "sourceApi.putVariable/getVariable",
            "只选择一个 JavaScript 书源导出或分享",
        )

        requiredText.forEach { text ->
            assertTrue("Missing current JS source contract: $text", guide.contains(text))
        }
        assertFalse(guide.contains("source.getLoginInfo()"))
        assertFalse(guide.contains("getReviewSummary"))
    }

    private fun source(relativePath: String): String {
        return File(repositoryRoot(), relativePath).readText()
    }

    private fun String.countOccurrences(value: String): Int {
        return Regex(Regex.escape(value)).findAll(this).count()
    }

    private fun repositoryRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }
}
