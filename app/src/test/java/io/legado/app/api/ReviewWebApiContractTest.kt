package io.legado.app.api

import com.google.gson.JsonParser
import io.legado.app.api.controller.rewriteLegacyReviewImages
import io.legado.app.api.controller.rewriteLegacyReviewResult
import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReviewWebApiContractTest {

    @Test
    fun `web review routes keep response next URLs on the server`() {
        val server = readProjectFile("app/src/main/java/io/legado/app/web/HttpServer.kt")
        val controller = readProjectFile(
            "app/src/main/java/io/legado/app/api/controller/ReviewController.kt"
        )

        assertTrue(server.contains("\"/getReviewSummary\" -> ReviewController.getSummary"))
        assertTrue(server.contains("\"/getReviewDetail\" -> ReviewController.getDetail"))
        assertTrue(server.contains("\"/getReviewReplies\" -> ReviewController.getReplies"))
        assertTrue(controller.contains("LruCache<String, DetailCursor>(64)"))
        assertTrue(controller.contains("UUID.randomUUID().toString()"))
        assertTrue(controller.contains("detailCursors[it]"))
        assertTrue(controller.contains("synchronized(detailCursors)"))
        assertFalse(controller.contains("detailCursors.remove"))
        assertTrue(controller.contains("state.context == cursorContext && state.page == page"))
        assertTrue(controller.contains("value == -1 || value > 0"))
        assertTrue(controller.contains("error(\"当前段评规则没有更多页\")"))
        assertTrue(controller.contains("hasMore = result.items.isNotEmpty() && nextCursor != null"))
        assertTrue(controller.contains("ReviewRuleParser.parseSummary"))
        assertTrue(controller.contains("ReviewRuleParser.parseDetailPage"))
        assertTrue(controller.contains("ReviewRuleParser.parseReplyPage"))
        assertTrue(controller.contains("JsSourceReview.getReviewSummaryAwait"))
        assertTrue(controller.contains("JsSourceReview.getReviewDetailAwait"))
        assertTrue(controller.contains("JsSourceReview.getReviewRepliesAwait"))
        val replies = controller.substringAfter("fun getReplies(")
            .substringBefore("fun openLegacyReview(")
        assertTrue(replies.indexOf("if (source.isJsSource())") < replies.indexOf("val rule = source.ruleReview"))
        assertFalse(controller.contains("parameters[\"nextUrl\"]"))
    }

    @Test
    fun `web reader opens native paragraph reviews`() {
        val chapter = readProjectFile("modules/web/src/views/BookChapter.vue")
        val content = readProjectFile("modules/web/src/components/ChapterContent.vue")
        val dialog = readProjectFile("modules/web/src/components/ReviewDialog.vue")

        assertTrue(chapter.contains("API.getReviewSummary"))
        assertTrue(chapter.contains("@open-review=\"openReview\""))
        assertTrue(content.contains("ChatDotRound"))
        assertTrue(content.contains("openReview(-1)"))
        assertTrue(dialog.contains("API.getReviewDetail"))
        assertTrue(dialog.contains("API.getReviewReplies"))
        assertTrue(dialog.contains("isImageBadge"))
        assertTrue(dialog.contains("reviewIdentity"))
        assertTrue(dialog.contains("openImage(item.imageUrl)"))
        assertTrue(dialog.contains("openImage(reply.imageUrl)"))
        assertTrue(dialog.contains("proxyImageUrl(url, 2048)"))
        assertTrue(dialog.contains(":url-list=\"[previewUrl]\""))
        assertTrue(dialog.contains("@closed=\"previewUrl = ''\""))
        assertFalse(dialog.contains(":src=\"item.audioUrl\""))
    }

    @Test
    fun `web review response models keep JSON names after minification`() {
        val parser = readProjectFile(
            "app/src/main/java/io/legado/app/model/analyzeRule/ReviewRuleParser.kt"
        )
        val controller = readProjectFile(
            "app/src/main/java/io/legado/app/api/controller/ReviewController.kt"
        )

        assertTrue(parser.contains("@Keep\n    internal data class SummaryResult("))
        assertTrue(parser.contains("@Keep\n    internal data class DetailItem("))
        assertTrue(controller.contains("@Keep\n    private data class ReviewPage("))
    }

    @Test
    fun `legacy review pages keep source execution behind the parent token bridge`() {
        val server = readProjectFile("app/src/main/java/io/legado/app/web/HttpServer.kt")
        val controller = readProjectFile(
            "app/src/main/java/io/legado/app/api/controller/ReviewController.kt"
        )
        val axios = readProjectFile("modules/web/src/api/axios.ts")
        val api = readProjectFile("modules/web/src/api/api.ts")
        val apiIndex = readProjectFile("modules/web/src/api/index.ts")
        val dialog = readProjectFile("modules/web/src/components/LegacyReviewDialog.vue")

        assertTrue(server.contains("uri == \"/legacyReviewPage\""))
        assertTrue(server.contains("\"/openLegacyReview\" -> ReviewController.openLegacyReview"))
        assertTrue(server.contains("\"/runLegacyReview\" -> ReviewController.runLegacyReview"))
        assertTrue(server.contains("sandbox allow-scripts allow-modals"))
        assertTrue(server.contains("connect-src 'none'"))
        assertTrue(server.contains("script-src 'unsafe-inline' 'unsafe-eval'"))
        assertTrue(server.contains("style-src 'unsafe-inline'"))
        assertFalse(server.contains("script-src https: 'unsafe-inline'"))
        assertFalse(server.contains("style-src http: https: 'unsafe-inline'"))
        assertTrue(server.contains("frame-src 'self' http: https:"))
        val vueHtmlHeaders = server.substringAfter(
            "if (uri.startsWith(\"/vue/\") && uri.endsWith(\".html\"))"
        ).substringBefore("}")
        assertTrue(vueHtmlHeaders.contains("addHeader(\"Cache-Control\", \"no-cache\")"))
        assertTrue(server.contains("if (uri == \"/legacyReviewPage\") \"<redacted>\""))
        assertTrue(axios.contains("'openLegacyReview'"))
        assertFalse(axios.contains("'legacyReviewPage'"))
        assertTrue(axios.contains("'runLegacyReview'"))
        assertTrue(api.contains("new URL('legacyReviewPage', legado_http_entry_point)"))
        assertTrue(api.contains("url.searchParams.set('id', session.id)"))
        assertTrue(api.contains("url.searchParams.set('nonce', session.nonce)"))
        assertFalse(api.contains("responseType: 'text'"))
        assertTrue(dialog.contains(":src=\"pageUrl\""))
        assertFalse(dialog.contains(":srcdoc=\"pageHtml\""))
        assertTrue(dialog.contains("sandbox=\"allow-scripts allow-modals\""))
        assertTrue(dialog.contains("allow=\"fullscreen\""))
        assertFalse(dialog.contains("allow-same-origin"))
        assertTrue(dialog.contains("message.nonce !== props.sessionNonce"))
        assertTrue(controller.contains("new MessageChannel()"))
        listOf("url", "index", "src", "id", "script", "nonce").forEach { field ->
            assertTrue(controller.contains("@SerializedName(\"$field\")"))
        }
        assertTrue(controller.contains("[channel.port2]"))
        assertTrue(dialog.contains("event.ports[0]"))
        assertTrue(dialog.contains("replyPort.postMessage"))
        assertFalse(dialog.contains("frameWindow.postMessage"))
        assertTrue(controller.contains(".replace(\"<\", \"\\\\u003c\")"))
        assertTrue(controller.contains("Math.max(100, Number(delay) || 0)"))
        assertTrue(controller.contains("document.addEventListener('click'"))
        assertTrue(controller.contains("image instanceof HTMLImageElement"))
        assertTrue(controller.contains("type: 'legado-legacy-review-image'"))
        assertTrue(controller.contains("image.currentSrc || image.src"))
        assertTrue(controller.contains("const bookUrl = ${'$'}{GSON.toJson(bookUrl)"))
        assertTrue(controller.contains("new URL('/image', window.location.href)"))
        assertTrue(controller.contains("/\\.(?:heic|heif)(?:[?#]|${'$'})/i"))
        assertTrue(controller.contains("target.pathname === '/image'"))
        assertTrue(controller.contains("target.searchParams.has('path')"))
        assertTrue(controller.contains("MutationObserver"))
        assertTrue(controller.contains("observeImages();"))
        assertTrue(controller.contains("data-legado-original-src"))
        assertTrue(controller.contains("image.setAttribute('data-original', proxied)"))
        assertFalse(controller.contains("wsrv.nl"))
        assertFalse(controller.contains("fetch('runLegacyReview'"))
        assertTrue(server.contains("http-equiv=\\\"Content-Security-Policy\\\""))
        assertTrue(controller.contains("if (nonce != session.nonce) return null"))
        assertTrue(controller.contains("showError(event.data?.error)"))
        assertTrue(controller.contains("event.data?.result == null"))
        assertTrue(controller.contains("finishLoading();\n                      resolve"))
        assertTrue(controller.contains("resolve(String(event.data.result))"))
        assertTrue(controller.contains("旧评论脚本未返回结果"))
        assertFalse(controller.contains("?.toString().orEmpty()"))
        assertTrue(controller.contains("channel.port1.onmessageerror"))
        assertTrue(controller.contains("showError('评论加载超时')"))
        assertTrue(controller.contains("window.clearTimeout(timeout)"))
        assertTrue(server.contains("allowedFrameAncestor(page?.frameOrigin)"))
        assertFalse(server.contains("frame-ancestors 'none'"))
        assertFalse(apiIndex.contains("LeagdoApiResponseKeys.length = 0"))
        assertTrue(apiIndex.contains("throw new Error('后端返回内容格式错误')"))
        assertTrue(dialog.contains("response.data.errorMsg || '评论加载失败'"))
        assertTrue(dialog.contains("String(error)) || '评论加载失败'"))
        assertTrue(dialog.contains("<el-image-viewer"))
        assertTrue(dialog.contains(":url-list=\"[previewUrl]\""))
        assertTrue(dialog.contains("message.type === 'legado-legacy-review-image'"))
        assertTrue(dialog.contains("previewUrl.value = message.src"))
        val imageBranch = dialog.indexOf("message.type === 'legado-legacy-review-image'")
        assertTrue(dialog.indexOf("event.source !== frameWindow") in 0 until imageBranch)
        assertTrue(dialog.indexOf("message.nonce !== props.sessionNonce") in 0 until imageBranch)
    }

    @Test
    fun `legacy review HEIF URLs are rewritten in initial and JSON HTML`() {
        val imageUrl = "https://cdn.example/review.heic?token=a&x-signature=b"
        val bookUrl = "https://books.example/book?id=1"
        val html = "<img src=\"$imageUrl\" data-original=\"$imageUrl\"><img src=\"https://cdn.example/review.jpg\">"

        val rewritten = rewriteLegacyReviewImages(html, bookUrl)
        assertFalse(rewritten.contains(imageUrl))
        assertTrue(rewritten.contains("/image?path=https%3A%2F%2Fcdn.example%2Freview.heic"))
        assertTrue(rewritten.contains("&amp;url=https%3A%2F%2Fbooks.example%2Fbook%3Fid%3D1"))
        assertTrue(rewritten.contains("https://cdn.example/review.jpg"))

        val result = rewriteLegacyReviewResult(
            GSON.toJson(mapOf("html" to html, "refferContent" to "<span>3</span>")),
            bookUrl,
        )
        val resultJson = JsonParser.parseString(result).asJsonObject
        assertTrue(resultJson["html"].asString.contains("/image?path="))
        assertTrue(resultJson["html"].asString.contains("&amp;url="))
        assertEquals("<span>3</span>", resultJson["refferContent"].asString)
    }

    private fun readProjectFile(path: String): String {
        val userDirectory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        val repositoryRoot = generateSequence(userDirectory) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
        requireNotNull(repositoryRoot) { "Repository root not found from $userDirectory" }
        return File(repositoryRoot, path).readText().replace("\r\n", "\n")
    }
}
