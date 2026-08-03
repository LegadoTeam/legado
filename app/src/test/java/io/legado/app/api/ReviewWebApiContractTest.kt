package io.legado.app.api

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
        assertFalse(dialog.contains(":src=\"item.audioUrl\""))
    }

    @Test
    fun `legacy review pages keep source execution behind the parent token bridge`() {
        val server = readProjectFile("app/src/main/java/io/legado/app/web/HttpServer.kt")
        val controller = readProjectFile(
            "app/src/main/java/io/legado/app/api/controller/ReviewController.kt"
        )
        val axios = readProjectFile("modules/web/src/api/axios.ts")
        val dialog = readProjectFile("modules/web/src/components/LegacyReviewDialog.vue")

        assertTrue(server.contains("uri == \"/legacyReviewPage\""))
        assertTrue(server.contains("\"/openLegacyReview\" -> ReviewController.openLegacyReview"))
        assertTrue(server.contains("\"/runLegacyReview\" -> ReviewController.runLegacyReview"))
        assertTrue(server.contains("sandbox allow-scripts allow-modals"))
        assertTrue(server.contains("connect-src 'none'"))
        assertTrue(axios.contains("'openLegacyReview'"))
        assertTrue(axios.contains("'legacyReviewPage'"))
        assertTrue(axios.contains("'runLegacyReview'"))
        assertTrue(dialog.contains(":srcdoc=\"pageHtml\""))
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
        assertFalse(controller.contains("fetch('runLegacyReview'"))
        assertTrue(server.contains("http-equiv=\\\"Content-Security-Policy\\\""))

        assertTrue(server.contains("frame-ancestors 'none'"))
    }

    private fun readProjectFile(path: String): String {
        val userDirectory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        val repositoryRoot = generateSequence(userDirectory) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
        requireNotNull(repositoryRoot) { "Repository root not found from $userDirectory" }
        return File(repositoryRoot, path).readText()
    }
}
