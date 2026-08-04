package io.legado.app.api.controller

import androidx.collection.LruCache
import com.google.gson.annotations.SerializedName
import com.script.rhino.runScriptWithContext
import io.legado.app.api.ReturnData
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setChapter
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.ReviewRuleParser
import io.legado.app.model.jsSource.JsSourceReview
import io.legado.app.ui.rss.read.RssJsExtensions
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.coroutines.coroutineContext

private val legacyReviewClickPattern = Regex(
    """^(?:getDP\(\s*\d+\s*,\s*\d+\s*\)|getZP\(\s*\d+\s*\))$"""
)

internal fun parseLegacyReviewClickScript(src: String): String? {
    val matcher = AnalyzeUrl.paramPattern.matcher(src)
    if (!matcher.find()) return null
    val click = GSON.fromJsonObject<Map<String, String>>(src.substring(matcher.end()))
        .getOrNull()
        ?.get("click")
        ?.trim()
        ?: return null
    return click.takeIf(legacyReviewClickPattern::matches)
}

object ReviewController {

    private data class ReviewContext(
        val book: Book,
        val chapter: BookChapter,
        val source: BookSource?,
    )

    private data class CursorContext(
        val bookUrl: String,
        val chapterIndex: Int,
        val sourceKey: String,
        val ruleHash: Int,
        val paragraphIndex: Int,
        val paragraphData: String,
    )

    private data class DetailCursor(
        val context: CursorContext,
        val page: Int,
        val url: String,
    )

    private data class ReviewPage(
        val items: List<ReviewRuleParser.DetailItem>,
        val nextCursor: String? = null,
        val hasMore: Boolean = false,
    )

    private data class LegacyReviewOpenRequest(
        @SerializedName("url")
        val url: String = "",
        @SerializedName("index")
        val index: Int = -1,
        @SerializedName("src")
        val src: String = "",
    )

    private data class LegacyReviewRunRequest(
        @SerializedName("id")
        val id: String = "",
        @SerializedName("script")
        val script: String = "",
    )

    private data class LegacyReviewBrowserPage(
        val url: String,
        val html: String?,
        val preloadJs: String?,
    )

    internal data class LegacyReviewWebPage(
        val html: String,
        val frameOrigin: String?,
    )

    private data class LegacyReviewSession(
        val bookUrl: String,
        val chapterIndex: Int,
        val sourceKey: String,
        val nonce: String,
        val page: LegacyReviewBrowserPage,
        val frameOrigin: String?,
        val expiresAt: Long,
    )

    private data class LegacyReviewSessionId(
        @SerializedName("id")
        val id: String,
        @SerializedName("nonce")
        val nonce: String,
    )

    private class LegacyReviewJsExtensions(
        source: BaseSource,
        private val onShowBrowser: (LegacyReviewBrowserPage) -> Unit = {},
    ) : RssJsExtensions(null, source, BookType.text) {

        override fun showBrowser(
            url: String,
            html: String?,
            preloadJs: String?,
            config: String?,
        ) {
            onShowBrowser(LegacyReviewBrowserPage(url, html, preloadJs))
        }
    }

    private val detailCursors = LruCache<String, DetailCursor>(64)
    private val legacyReviewSessions = LruCache<String, LegacyReviewSession>(16)

    fun getSummary(parameters: Map<String, List<String>>): ReturnData = respond {
        val context = requireContext(parameters)
        val source = context.source
            ?: return@respond ReviewRuleParser.SummaryResult(emptyMap(), emptyMap())
        if (source.isJsSource()) {
            return@respond JsSourceReview.getReviewSummaryAwait(
                source,
                context.book,
                context.chapter,
            ) ?: ReviewRuleParser.SummaryResult(emptyMap(), emptyMap())
        }

        val rule = source.ruleReview
        val summaryUrl = rule?.reviewSummaryUrl?.takeIf { it.isNotBlank() }
        if (rule == null || !rule.enabled || summaryUrl == null ||
            rule.summaryListRule.isNullOrBlank() ||
            rule.summaryParagraphIndexRule.isNullOrBlank() ||
            rule.summaryCountRule.isNullOrBlank()
        ) {
            return@respond ReviewRuleParser.SummaryResult(emptyMap(), emptyMap())
        }
        val analyzeUrl = AnalyzeUrl(
            summaryUrl,
            baseUrl = context.chapter.url,
            source = source,
            ruleData = context.book,
            chapter = context.chapter,
            coroutineContext = coroutineContext,
        )
        val body = analyzeUrl.getStrResponseAwait(useWebView = false).body.orEmpty()
        ReviewRuleParser.parseSummary(
            body,
            rule,
            source,
            context.book,
            context.chapter,
            analyzeUrl.url,
            coroutineContext,
        ) ?: ReviewRuleParser.SummaryResult(emptyMap(), emptyMap())
    }

    fun getDetail(parameters: Map<String, List<String>>): ReturnData = respond {
        val context = requireContext(parameters)
        val source = requireNotNull(context.source) { "未找到书源" }
        val paragraphIndex = requireParagraphIndex(parameters)
        val paragraphData = parameters["paraData"]?.firstOrNull() ?: paragraphIndex.toString()
        val page = requireInt(parameters, "page", 1)

        if (source.isJsSource()) {
            require(parameters["cursor"]?.firstOrNull().isNullOrBlank()) {
                "JavaScript 段评不使用分页游标"
            }
            val result = JsSourceReview.getReviewDetailAwait(
                source = source,
                book = context.book,
                chapter = context.chapter,
                paragraphIndex = paragraphIndex,
                paragraphData = paragraphData,
                page = page,
            ) ?: return@respond ReviewPage(emptyList())
            return@respond ReviewPage(
                items = result.items,
                hasMore = result.items.isNotEmpty() && !result.nextPageUrl.isNullOrBlank(),
            )
        }

        val rule = requireNotNull(source.ruleReview) { "段评规则未配置" }
        require(rule.enabled) { "段评规则未启用" }
        require(!rule.detailListRule.isNullOrBlank() && !rule.detailContentRule.isNullOrBlank()) {
            "段评详情规则不完整"
        }
        val firstPageUrl = requireNotNull(rule.reviewDetailUrl?.takeIf { it.isNotBlank() }) {
            "段评详情地址未配置"
        }
        val nextPageRule = rule.reviewDetailNextPageUrl?.takeIf { it.isNotBlank() }
        val cursorContext = CursorContext(
            bookUrl = context.book.bookUrl,
            chapterIndex = context.chapter.index,
            sourceKey = source.getKey(),
            ruleHash = rule.hashCode(),
            paragraphIndex = paragraphIndex,
            paragraphData = paragraphData,
        )
        val cursor = parameters["cursor"]?.firstOrNull()?.takeIf { it.isNotBlank() }
        val detailUrl = when {
            page == 1 -> {
                require(cursor == null) { "首段评页不能使用分页游标" }
                firstPageUrl
            }

            nextPageRule == null -> {
                error("当前段评规则没有更多页")
            }

            else -> {
                val state = requireNotNull(cursor?.let {
                    synchronized(detailCursors) { detailCursors[it] }
                }) {
                    "段评分页面游标无效或已过期"
                }
                require(state.context == cursorContext && state.page == page) {
                    "段评分页面游标无效或已过期"
                }
                state.url
            }
        }
        val analyzeUrl = AnalyzeUrl(
            detailUrl,
            page = page,
            extraParams = mapOf(
                "paraIndex" to paragraphIndex.toString(),
                "paraData" to paragraphData,
                "page" to page.toString(),
            ),
            baseUrl = context.chapter.url,
            source = source,
            ruleData = context.book,
            chapter = context.chapter,
            coroutineContext = coroutineContext,
        )
        val body = analyzeUrl.getStrResponseAwait(useWebView = false).body.orEmpty()
        val result = ReviewRuleParser.parseDetailPage(
            body = body,
            rule = rule,
            nextPageRule = nextPageRule,
            baseUrl = analyzeUrl.url,
            source = source,
            book = context.book,
            chapter = context.chapter,
            context = coroutineContext,
            paraIndex = paragraphIndex.toString(),
            paraData = paragraphData,
            page = page.toString(),
        )
        val nextCursor = result.nextPageUrl
            ?.takeIf { result.items.isNotEmpty() && it.isNotBlank() }
            ?.let { nextUrl ->
                UUID.randomUUID().toString().also {
                    synchronized(detailCursors) {
                        detailCursors.put(it, DetailCursor(cursorContext, page + 1, nextUrl))
                    }
                }
            }
        ReviewPage(
            items = result.items,
            nextCursor = nextCursor,
            hasMore = result.items.isNotEmpty() && nextCursor != null,
        )
    }

    fun getReplies(parameters: Map<String, List<String>>): ReturnData = respond {
        val context = requireContext(parameters)
        val source = context.source ?: return@respond ReviewPage(emptyList())
        if (source.isJsSource()) return@respond ReviewPage(emptyList())
        val rule = source.ruleReview ?: return@respond ReviewPage(emptyList())
        val replyUrl = rule.reviewQuoteUrl?.takeIf { it.isNotBlank() }
            ?: return@respond ReviewPage(emptyList())
        if (!rule.enabled || rule.replyListRule.isNullOrBlank() ||
            rule.replyContentRule.isNullOrBlank()
        ) {
            return@respond ReviewPage(emptyList())
        }

        val paragraphIndex = requireParagraphIndex(parameters)
        val paragraphData = parameters["paraData"]?.firstOrNull() ?: paragraphIndex.toString()
        val reviewId = requireParameter(parameters, "reviewId").also {
            require(it.isNotBlank()) { "参数 reviewId 不能为空" }
        }
        val page = requireInt(parameters, "page", 1)
        val analyzeUrl = AnalyzeUrl(
            replyUrl,
            page = page,
            extraParams = mapOf(
                "paraIndex" to paragraphIndex.toString(),
                "paraData" to paragraphData,
                "reviewId" to reviewId,
                "page" to page.toString(),
            ),
            baseUrl = context.chapter.url,
            source = source,
            ruleData = context.book,
            chapter = context.chapter,
            coroutineContext = coroutineContext,
        )
        val body = analyzeUrl.getStrResponseAwait(useWebView = false).body
            ?.takeIf { it.isNotBlank() }
            ?: error("段评回复内容为空")
        val items = ReviewRuleParser.parseReplyPage(
            body = body,
            rule = rule,
            baseUrl = analyzeUrl.url,
            source = source,
            book = context.book,
            chapter = context.chapter,
            context = coroutineContext,
            paraIndex = paragraphIndex.toString(),
            paraData = paragraphData,
            page = page.toString(),
        )
        ReviewPage(items = items, hasMore = items.isNotEmpty())
    }

    fun openLegacyReview(postData: String?, frameOrigin: String?): ReturnData = respond {
        val request = GSON.fromJsonObject<LegacyReviewOpenRequest>(postData).getOrThrow()
        val context = requireContext(request.url, request.index)
        val source = requireNotNull(context.source) { "未找到书源" }
        val click = requireNotNull(parseLegacyReviewClickScript(request.src)) {
            "不是受支持的旧段评或章评链接"
        }
        var browserPage: LegacyReviewBrowserPage? = null
        executeLegacyReviewScript(context, click, request.src) {
            browserPage = it
        }
        val page = requireNotNull(browserPage) { "旧评论脚本未返回页面" }
        require(!page.html.isNullOrBlank()) { "旧评论页面内容为空" }

        val id = UUID.randomUUID().toString()
        val nonce = UUID.randomUUID().toString()
        synchronized(legacyReviewSessions) {
            legacyReviewSessions.put(
                id,
                LegacyReviewSession(
                    bookUrl = context.book.bookUrl,
                    chapterIndex = context.chapter.index,
                    sourceKey = source.getKey(),
                    nonce = nonce,
                    page = page,
                    frameOrigin = frameOrigin,
                    expiresAt = System.currentTimeMillis() + LEGACY_REVIEW_SESSION_TTL,
                )
            )
        }
        LegacyReviewSessionId(id, nonce)
    }

    fun runLegacyReview(postData: String?): ReturnData = respond {
        val request = GSON.fromJsonObject<LegacyReviewRunRequest>(postData).getOrThrow()
        require(request.script.isNotBlank() && request.script.length <= MAX_LEGACY_REVIEW_SCRIPT) {
            "旧评论脚本为空或过长"
        }
        val session = requireNotNull(getLegacyReviewSession(request.id)) {
            "旧评论会话无效或已过期"
        }
        val context = requireContext(session.bookUrl, session.chapterIndex)
        require(context.source?.getKey() == session.sourceKey) { "书源已变更，请重新打开评论" }
        val source = requireNotNull(context.source) { "未找到书源" }
        runScriptWithContext {
            AnalyzeRule(context.book, source)
                .setChapter(context.chapter)
                .setCoroutineContext(coroutineContext)
                .evalJS(request.script)
        }?.toString().orEmpty()
    }

    internal fun getLegacyReviewPage(
        parameters: Map<String, List<String>>,
    ): LegacyReviewWebPage? {
        val id = parameters["id"]?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val nonce = parameters["nonce"]?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val session = getLegacyReviewSession(id) ?: return null
        if (nonce != session.nonce) return null
        return LegacyReviewWebPage(
            html = injectLegacyReviewBridge(session.nonce, session.page),
            frameOrigin = session.frameOrigin,
        )
    }

    private suspend fun executeLegacyReviewScript(
        context: ReviewContext,
        script: String,
        result: String,
        onShowBrowser: (LegacyReviewBrowserPage) -> Unit = {},
    ): Any? {
        val source = requireNotNull(context.source) { "未找到书源" }
        val java = LegacyReviewJsExtensions(source, onShowBrowser)
        return runScriptWithContext {
            source.evalJS(script) {
                put("java", java)
                put("book", context.book)
                put("chapter", context.chapter)
                put("result", result)
            }
        }
    }

    private fun getLegacyReviewSession(id: String): LegacyReviewSession? {
        return synchronized(legacyReviewSessions) {
            val session = legacyReviewSessions[id] ?: return@synchronized null
            if (session.expiresAt <= System.currentTimeMillis()) {
                legacyReviewSessions.remove(id)
                null
            } else {
                session
            }
        }
    }

    private fun injectLegacyReviewBridge(
        nonce: String,
        page: LegacyReviewBrowserPage,
    ): String {
        val bridge = """
            <script>
            (() => {
              const nativeSetInterval = window.setInterval.bind(window);
              window.setInterval = (callback, delay, ...args) =>
                nativeSetInterval(callback, Math.max(100, Number(delay) || 0), ...args);
              const nonce = ${GSON.toJson(nonce)};
              const showError = error => {
                const message = String(error || '评论加载失败');
                document.getElementById('loading')?.classList.add('hidden');
                const viewer = document.getElementById('viewer');
                if (viewer) viewer.textContent = message;
                return new Error(message);
              };
              window.run = async function(code) {
                return new Promise((resolve, reject) => {
                  const channel = new MessageChannel();
                  channel.port1.onmessage = event => {
                    if (event.data?.error) reject(showError(event.data.error));
                    else resolve(event.data?.result == null ? '' : String(event.data.result));
                    channel.port1.close();
                  };
                  channel.port1.onmessageerror = () => {
                    reject(showError('评论响应解析失败'));
                    channel.port1.close();
                  };
                  window.parent.postMessage({
                    type: 'legado-legacy-review-run',
                    nonce,
                    script: String(code)
                  }, '*', [channel.port2]);
                });
              };
              window.java = { upConfig() {} };
              const preload = ${GSON.toJson(page.preloadJs.orEmpty()).replace("<", "\\u003c")};
              if (preload) (0, eval)(preload);
            })();
            </script>
        """.trimIndent()
        val base = page.url.takeIf { it.isNotBlank() }?.let {
            "<base href=\"${escapeHtmlAttribute(it)}\">"
        }.orEmpty()
        val injection = base + bridge
        val headIndex = page.html!!.indexOf("<head", ignoreCase = true)
        if (headIndex >= 0) {
            val headEnd = page.html.indexOf('>', headIndex)
            if (headEnd >= 0) {
                return page.html.substring(0, headEnd + 1) + injection +
                        page.html.substring(headEnd + 1)
            }
        }
        return injection + page.html
    }

    private fun escapeHtmlAttribute(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun respond(block: suspend () -> Any): ReturnData {
        return try {
            ReturnData().setData(runBlocking { block() })
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ReturnData().setErrorMsg(error.localizedMessage ?: "段评加载失败")
        }
    }

    private fun requireContext(parameters: Map<String, List<String>>): ReviewContext {
        val bookUrl = requireParameter(parameters, "url").also {
            require(it.isNotBlank()) { "参数 url 不能为空" }
        }
        val chapterIndex = requireInt(parameters, "index", 0)
        return requireContext(bookUrl, chapterIndex)
    }

    private fun requireContext(bookUrl: String, chapterIndex: Int): ReviewContext {
        require(bookUrl.isNotBlank()) { "参数 url 不能为空" }
        require(chapterIndex >= 0) { "参数 index 无效" }
        val book = requireNotNull(appDb.bookDao.getBook(bookUrl)) { "未找到书籍" }
        val chapter = requireNotNull(appDb.bookChapterDao.getChapter(bookUrl, chapterIndex)) {
            "未找到章节"
        }
        return ReviewContext(
            book = book,
            chapter = chapter,
            source = appDb.bookSourceDao.getBookSource(book.origin),
        )
    }

    private fun requireParameter(
        parameters: Map<String, List<String>>,
        name: String,
    ): String = requireNotNull(parameters[name]?.firstOrNull()) { "参数 $name 不能为空" }

    private fun requireParagraphIndex(parameters: Map<String, List<String>>): Int {
        val value = requireNotNull(requireParameter(parameters, "paraIndex").toIntOrNull()) {
            "参数 paraIndex 无效"
        }
        require(value == -1 || value > 0) { "参数 paraIndex 无效" }
        return value
    }

    private fun requireInt(
        parameters: Map<String, List<String>>,
        name: String,
        minimum: Int,
    ): Int {
        val value = requireNotNull(requireParameter(parameters, name).toIntOrNull()) {
            "参数 $name 无效"
        }
        require(value >= minimum) { "参数 $name 无效" }
        return value
    }

    private const val LEGACY_REVIEW_SESSION_TTL = 2 * 60 * 60 * 1000L
    private const val MAX_LEGACY_REVIEW_SCRIPT = 64 * 1024
}
