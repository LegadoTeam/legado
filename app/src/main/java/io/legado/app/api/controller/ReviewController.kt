package io.legado.app.api.controller

import androidx.collection.LruCache
import io.legado.app.api.ReturnData
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.ReviewRuleParser
import io.legado.app.model.jsSource.JsSourceReview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.coroutines.coroutineContext

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

    private val detailCursors = LruCache<String, DetailCursor>(64)

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
}
