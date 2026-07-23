package io.legado.app

import io.legado.app.data.entities.BookSource
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test

/**
 * 书源集成测试
 *
 * 运行在 Android 模拟器或真机上（Instrumentation 测试），使用完整的 app 解析流程。
 *
 * 使用方式：
 * 1. 将 [BOOK_SOURCE_JSON] 替换为你要测试的完整书源 JSON
 * 2. 将 [SEARCH_KEY] 替换为用于测试搜索的关键词
 * 3. 在 Android Studio 中右键 -> Run
 *
 * 测试流程：
 * - 解析书源 JSON
 * - 搜索 -> 获取书籍详情 -> 获取目录 -> 获取第一章正文
 */
class BookSourceIntegrationTest {

    companion object {
        /**
         * 书源 JSON，请替换为你要测试的完整书源 JSON
         */
        private val BOOK_SOURCE_JSON = """
            {
    "bookSourceComment": "//By情无羁(yesui.me),25.02.03\n同站\n得奇小说：https://www.deqixs.com\n速读谷：https://www.sudugu.com\n笔趣阁：https://www.bvquge.com\n吞噬星空2：https://www.tunshixingkong2.com\n三叶小说：https://3yexs.com",
    "bookSourceName": "速读谷",
    "bookSourceType": 0,
    "bookSourceUrl": "https://www.sudugu.org",
    "bookUrlPattern": "https://www.sudugu.org/\\d+/",
    "concurrentRate": "20/60000",
    "customButton": false,
    "customOrder": 11,
    "enabled": true,
    "enabledCookieJar": false,
    "enabledExplore": true,
    "eventListener": false,
    "exploreUrl": "@js:\n// === 粘贴您的分类内容到这里 ===\nvar inputData = `°・*.☆ 全部榜单 ☆.*・°\n最近更新::/zuixin/{{page}}.html\n热门小说::/paihang/{{page}}.html\n连载小说::/lianzai/{{page}}.html\n完结小说::/wanjie/{{page}}.html\n玄幻小说::/xuanhuan/{{page}}.html\n仙侠小说::/xianxia/{{page}}.html\n都市小说::/dushi/{{page}}.html\n历史小说::/lishi/{{page}}.html\n科幻小说::/kehuan/{{page}}.html\n轻小说::/qing/{{page}}.html\n悬疑小说::/xuanyi/{{page}}.html\n游戏小说::/youxi/{{page}}.html\n奇幻小说::/qihuan/{{page}}.html\n诸天无限::/zhutianwuxian/{{page}}.html\n军事小说::/junshi/{{page}}.html\n重生小说::/chongsheng/{{page}}.html\n武侠小说::/wuxia/{{page}}.html\n体育小说::/tiyu/{{page}}.html\n言情小说::/yanqing/{{page}}.html`;\n\nvar categories = [];\nvar lines = inputData.split('\\n').filter(l => l.trim());\nvar items = [];\n\nfor (var i = 0; i < lines.length; i++) {\n    var parts = lines[i].split('::');\n    var title = parts[0] || \"\";\n    var url = (parts[1] || \"\").trim();\n    \n    if (!url) {\n        categories.push({\n            \"title\": title,\n            \"url\": \"\",\n            \"style\": {\"layout_flexGrow\":1,\"layout_flexBasisPercent\":1}\n        });\n    } else {\n        items.push({\n            \"title\": title,\n            \"url\": url,\n            \"style\": {\"layout_flexGrow\":1,\"layout_flexBasisPercent\":0.25}\n        });\n    }\n}\n\nfor (var j = 0; j < items.length; j++) {\n    categories.push(items[j]);\n}\n\nvar remainder = items.length % 3;\nif (remainder > 0) {\n    for (var k = 0; k < 3 - remainder; k++) {\n        categories.push({\n            \"title\": \"----------\",\n            \"url\": \"\",\n            \"style\": {\"layout_flexGrow\":1,\"layout_flexBasisPercent\":0.25}\n        });\n    }\n}\n\nJSON.stringify(categories);",
    "header": "{\"User-Agent\": \"Mozilla/5.0 (Linux; Android 9) Mobile Safari/537.36\"}",
    "lastUpdateTime": "1784483524285",
    "respondTime": 8467,
    "ruleBookInfo": {
      "author": ".itemtxt@p.1@text##作者：",
      "coverUrl": ".item@img@src",
      "intro": ".des@p@text",
      "kind": ".itemtxt@p.0@text",
      "lastChapter": ".itemtxt@ul@li.0@a@text",
      "name": "h1@a@text",
      "wordCount": "h1@i@text"
    },
    "ruleContent": {
      "content": ".con@html",
      "nextContentUrl": "text.下一页@href",
      "replaceRegex": "##{{chapter.title}}.*|\\(本章完\\)|最⊥新⊥.*|必.+搜.+日.+最.*\n@js:\nvar r = result\nr=r.replace(/「/gi,'“')\n.replace(/」/gi,'”')"
    },
    "ruleExplore": [],
    "ruleSearch": {
      "author": ".itemtxt@p.1@text##作者：",
      "bookList": ".item",
      "bookUrl": "a@href",
      "coverUrl": "img@src",
      "kind": "span.1@text",
      "lastChapter": "ul@li.0@a@text",
      "name": "a.1@text||h2@text"
    },
    "ruleToc": {
      "chapterList": "#list@ul@li@a",
      "chapterName": "text##[\\(\\{（｛【].*[首为求更谢乐发推票章盟第补合感订加字KkWw\\/].*[\\)\\}）｝】]",
      "chapterUrl": "href",
      "nextTocUrl": "text.下一页@href"
    },
    "searchUrl": "/i/sor.aspx?key={{key}}",
    "weight": 0
  }
        """.trimIndent()

        /**
         * 搜索关键词，请替换为你想搜索的关键词
         */
        private val SEARCH_KEY = "离婚逆袭"
    }

    @Test
    fun testBookSourceFullFlow() = runBlocking {
        // ========== 步骤1：解析书源 JSON ==========
        println("===== 步骤1：解析书源 JSON =====")
        val source: BookSource = GSON.fromJson(BOOK_SOURCE_JSON, BookSource::class.java)
        Assert.assertNotNull("书源解析失败，返回 null", source)
        Assert.assertTrue("书源URL不能为空", source.bookSourceUrl.isNotBlank())
        Assert.assertTrue("书源名称不能为空", source.bookSourceName.isNotBlank())
        println("书源名称: ${source.bookSourceName}")
        println("书源URL: ${source.bookSourceUrl}")

        // ========== 步骤2：搜索 ==========
        println("===== 步骤2：搜索关键词 [$SEARCH_KEY] =====")
        val searchResults = try {
            WebBook.searchBookAwait(source, SEARCH_KEY)
        } catch (e: Exception) {
            Assert.fail("搜索失败: ${e.localizedMessage}")
            return@runBlocking
        }
        Assert.assertTrue("搜索结果为空，书源可能搜索失效", searchResults.isNotEmpty())
        val firstResult = searchResults.first()
        Assert.assertNotNull("搜索结果第一条为 null", firstResult)
        Assert.assertTrue("搜索结果书名为空", firstResult.name.isNotBlank())
        println("搜索结果数量: ${searchResults.size}")
        println("第一条结果:")
        println("  书名: ${firstResult.name}")
        println("  作者: ${firstResult.author}")
        println("  书籍URL: ${firstResult.bookUrl}")

        // ========== 步骤3：获取书籍详情 ==========
        println("===== 步骤3：获取书籍详情 =====")
        val book = firstResult.toBook()
        try {
            WebBook.getBookInfoAwait(source, book)
        } catch (e: Exception) {
            Assert.fail("获取书籍详情失败: ${e.localizedMessage}")
            return@runBlocking
        }
        Assert.assertTrue("书籍名称为空", book.name.isNotBlank())
        println("书籍名称: ${book.name}")
        println("书籍作者: ${book.author}")
        book.kind?.let { println("分类: $it") }
        book.intro?.let { println("简介: ${it.take(100)}...") }
        book.coverUrl?.let { println("封面: $it") }
        println("目录URL: ${book.tocUrl}")

        // ========== 步骤4：获取目录列表 ==========
        println("===== 步骤4：获取目录列表 =====")
        val chapterListResult = try {
            WebBook.getChapterListAwait(source, book)
        } catch (e: Exception) {
            Assert.fail("获取目录失败: ${e.localizedMessage}")
            return@runBlocking
        }
        Assert.assertTrue("获取目录失败", chapterListResult.isSuccess)
        val chapters = chapterListResult.getOrThrow()
        Assert.assertTrue("目录列表为空", chapters.isNotEmpty())
        println("目录总数: ${chapters.size}")
        val firstChapter = chapters.first()
        println("第一章: [${firstChapter.index}] ${firstChapter.title}")
        println("第一章URL: ${firstChapter.url}")

        // ========== 步骤5：获取第一章正文 ==========
        println("===== 步骤5：获取第一章正文 =====")
        val content = try {
            WebBook.getContentAwait(
                bookSource = source,
                book = book,
                bookChapter = firstChapter,
                needSave = false
            )
        } catch (e: Exception) {
            Assert.fail("获取正文失败: ${e.localizedMessage}")
            return@runBlocking
        }
        Assert.assertTrue("正文内容为空", content.isNotBlank())
        println("正文长度: ${content.length} 字符")
        println("正文预览:\n${content.take(200)}...")

        // ========== 全部通过 ==========
        println("===== 书源测试全部通过！ =====")
        println("书源: ${source.bookSourceName} (${source.bookSourceUrl})")
        println("搜索关键词: $SEARCH_KEY")
        println("测试书籍: ${book.name} - ${book.author}")
        println("目录数: ${chapters.size}")
        println("第一章: ${firstChapter.title}")
    }
}
