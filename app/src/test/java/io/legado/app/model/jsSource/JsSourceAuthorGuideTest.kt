package io.legado.app.model.jsSource

import org.junit.Assert.assertEquals
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
    fun `built in template remains importable`() {
        val script = source("app/src/main/assets/js_source_template.js")
        val source = JsSourceConfig.extract(script)

        assertTrue(script.contains("var config ="))
        assertTrue(script.contains("var Jsoup = org.jsoup.Jsoup;"))
        assertEquals("https://example.com", source.bookSourceUrl)
        assertEquals("示例 JS 书源", source.bookSourceName)
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

        assertTrue(script.contains("var config ="))
        assertEquals("https://example.com", source.bookSourceUrl)
        assertEquals("示例 JS 书源", source.bookSourceName)
        assertTrue(source.loginUi.orEmpty().contains("账号"))
        assertTrue(source.exploreUrl.orEmpty().contains("分类"))
        assertEquals(script, source.mainJs)
    }

    @Test
    fun `guide describes current JavaScript source contracts`() {
        val requiredText = listOf(
            "`config` 是脚本声明的普通配置对象",
            "`source` 是数据库中的运行时书源对象",
            "`sourceApi` 是 `source` 的兼容别名",
            "旧版脚本",
            "无关或未定义的 `config` 不影响旧版 `source` 导入",
            "source.getLoginInfo()",
            "sourceApi.getLoginInfo()",
            "function explore(url, page)",
            "function getContent(chapter, book, nextChapterUrl)",
            "`4` 视频",
            "`loginUi` 非空时必选",
            "`exploreUrl` 非空时必选",
            "文件源必选",
            "`downloadUrls`",
            "source.putVariable/getVariable",
            "只选择一个 JavaScript 书源导出或分享",
            "getReviewSummary(chapter, book)",
            "getReviewDetail(chapter, book, paraIndex, paraData, page)",
            "nextPageUrl",
        )

        requiredText.forEach { text ->
            assertTrue("Missing current JS source contract: $text", guide.contains(text))
        }
        assertTrue(guide.contains("只有同时声明 `getReviewSummary` 和 `getReviewDetail`"))
    }

    @Test
    fun `guide documents Java string wrapper boundaries`() {
        val requiredText = listOf(
            "`typeof chapter.title`",
            "`chapter.title.length()`",
            "`chapter.tag ? \"T\" : \"F\"`",
            "`chapter.title === \"第1章\"`",
            "`chapter.url.replace(/b/, \"X\")`",
            "`chapter.url.split(\"/\", -1).length`",
            "String(chapter.url || \"\")",
            "String(chapter.tag || \"\")",
        )

        requiredText.forEach { text ->
            assertTrue("Missing Java string boundary documentation: $text", guide.contains(text))
        }
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
