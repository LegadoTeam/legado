package io.legado.app.model.jsSource

import androidx.collection.LruCache
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.analyzeRule.ReviewRuleParser
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import java.util.ArrayDeque
import kotlin.coroutines.coroutineContext

internal object JsSourceReview {

    private val capabilityCache = LruCache<String, Boolean>(64)
    private val replyCapabilityCache = LruCache<String, Boolean>(64)

    fun rememberReviewCapability(source: BookSource, enabled: Boolean) {
        capabilityCache.put(capabilityKey(source), enabled)
    }

    fun hasReviewCapability(source: BookSource): Boolean {
        val key = capabilityKey(source)
        capabilityCache[key]?.let { return it }
        return JsSourceConfig.declaresReviewFunctions(source.mainJs.orEmpty()).also {
            capabilityCache.put(key, it)
        }
    }

    fun rememberReviewRepliesCapability(source: BookSource, enabled: Boolean) {
        replyCapabilityCache.put(capabilityKey(source), enabled)
    }

    fun hasReviewRepliesCapability(source: BookSource): Boolean {
        val key = capabilityKey(source)
        replyCapabilityCache[key]?.let { return it }
        return JsSourceConfig.declaresReviewRepliesFunction(source.mainJs.orEmpty()).also {
            replyCapabilityCache.put(key, it)
        }
    }

    suspend fun getReviewSummaryAwait(
        source: BookSource,
        book: Book,
        chapter: BookChapter,
    ): ReviewRuleParser.SummaryResult? {
        val capabilityKey = capabilityKey(source)
        if (capabilityCache[capabilityKey] == false) return null
        val call = JsSourceEngine(source, coroutineContext).callOptionalFunction(
            "getReviewSummary",
            listOf("chapter" to chapter, "book" to book),
        )
        if (!call.exists) {
            capabilityCache.put(capabilityKey, false)
            return null
        }
        capabilityCache.put(capabilityKey, true)
        val json = call.value ?: return emptySummary()
        val array = runCatching { GSON.fromJson(json, JsonArray::class.java) }.getOrNull()
            ?: return emptySummary()

        val counts = HashMap<Int, Int>()
        val keys = HashMap<Int, String>()
        array.forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            val paragraphIndex = item.optInt("paraIndex") ?: return@forEach
            val count = item.optInt("count") ?: 0
            if ((paragraphIndex == -1 || paragraphIndex > 0) && count > 0) {
                counts[paragraphIndex] = count
                keys[paragraphIndex] = item.optString("paraData") ?: paragraphIndex.toString()
            }
        }
        return ReviewRuleParser.SummaryResult(counts, keys)
    }

    suspend fun getReviewDetailAwait(
        source: BookSource,
        book: Book,
        chapter: BookChapter,
        paragraphIndex: Int,
        paragraphData: String,
        page: Int,
    ): ReviewRuleParser.DetailPage? {
        val json = JsSourceEngine(source, coroutineContext).callFunction(
            "getReviewDetail",
            listOf(
                "chapter" to chapter,
                "book" to book,
                "paraIndex" to paragraphIndex,
                "paraData" to paragraphData,
                "page" to page,
            ),
        ) ?: return null
        val result = runCatching { GSON.fromJson(json, JsonObject::class.java) }.getOrNull()
            ?: return null
        return parseDetailObject(result, chapter.url)
    }

    suspend fun getReviewRepliesAwait(
        source: BookSource,
        book: Book,
        chapter: BookChapter,
        paragraphIndex: Int,
        paragraphData: String,
        reviewId: String,
        page: Int,
    ): List<ReviewRuleParser.DetailItem>? {
        val call = JsSourceEngine(source, coroutineContext).callOptionalFunction(
            "getReviewReplies",
            listOf(
                "chapter" to chapter,
                "book" to book,
                "paraIndex" to paragraphIndex,
                "paraData" to paragraphData,
                "reviewId" to reviewId,
                "page" to page,
            ),
        )
        rememberReviewRepliesCapability(source, call.exists)
        if (!call.exists) return null
        val json = call.value ?: return emptyList()
        val result = runCatching { GSON.fromJson(json, JsonObject::class.java) }.getOrNull()
            ?: throw NoStackTraceException("JS源 getReviewReplies 返回格式错误")
        return parseReplyObject(result, chapter.url)
            ?: throw NoStackTraceException("JS源 getReviewReplies 返回格式错误,缺少 items 数组")
    }

    internal fun parseDetailObject(
        result: JsonObject,
        baseUrl: String,
    ): ReviewRuleParser.DetailPage? {
        val items = result.optArray("items") ?: return null
        return ReviewRuleParser.DetailPage(
            items = parseDetailItems(items, baseUrl),
            nextPageUrl = result.optString("nextPageUrl")?.takeIf { it.isNotBlank() },
        )
    }

    internal fun parseReplyObject(
        result: JsonObject,
        baseUrl: String,
    ): List<ReviewRuleParser.DetailItem>? {
        val items = result.optArray("items") ?: return null
        return flattenReplies(items, baseUrl)
    }

    private fun parseDetailItems(
        array: JsonArray,
        baseUrl: String,
    ): List<ReviewRuleParser.DetailItem> {
        return array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            parseDetailItem(item, baseUrl, flattenReplies(item.optArray("replies"), baseUrl))
        }
    }

    private fun parseDetailItem(
        item: JsonObject,
        baseUrl: String,
        replies: List<ReviewRuleParser.DetailItem> = emptyList(),
    ): ReviewRuleParser.DetailItem? {
        val rawContent = item.optContent("content")?.takeIf { it.isNotBlank() } ?: return null
        val protocol = ReviewRuleParser.parseContentProtocol(rawContent, baseUrl)
        val content = protocol?.text ?: if (protocol == null) rawContent else ""
        return ReviewRuleParser.DetailItem(
            id = item.optString("id"),
            avatar = item.optString("avatar")?.let { NetworkUtils.getAbsoluteURL(baseUrl, it) },
            name = item.optString("name"),
            replyToName = protocol?.replyToName,
            badges = item.optStrings("badge")
                .flatMap { ReviewRuleParser.splitBadgeValue(it) }
                .distinct(),
            content = content,
            imageUrl = protocol?.imageUrl,
            audioUrl = protocol?.audioUrl,
            time = protocol?.time,
            likeCount = protocol?.likeCount,
            replyCount = protocol?.replyCount,
            replies = replies,
        )
    }

    private fun flattenReplies(
        replies: JsonArray?,
        baseUrl: String,
    ): List<ReviewRuleParser.DetailItem> {
        if (replies == null || replies.size() == 0) return emptyList()
        val stack = ArrayDeque<JsonObject>()
        pushObjectsInReverse(stack, replies)
        return buildList {
            while (stack.isNotEmpty()) {
                val item = stack.removeLast()
                parseDetailItem(item, baseUrl)?.let(::add)
                item.optArray("replies")?.let { pushObjectsInReverse(stack, it) }
            }
        }
    }

    private fun pushObjectsInReverse(stack: ArrayDeque<JsonObject>, array: JsonArray) {
        for (index in array.size() - 1 downTo 0) {
            (array[index] as? JsonObject)?.let(stack::addLast)
        }
    }

    private fun emptySummary() = ReviewRuleParser.SummaryResult(emptyMap(), emptyMap())

    private fun capabilityKey(source: BookSource): String {
        return "${source.getKey()}|${source.mainJs.hashCode()}"
    }

    private fun JsonObject.optString(key: String): String? {
        val element = get(key) ?: return null
        if (element.isJsonNull) return null
        return runCatching { element.asString }.getOrNull()
    }

    private fun JsonObject.optContent(key: String): String? {
        val element = get(key) ?: return null
        if (element.isJsonNull) return null
        if (element.isJsonObject) return element.toString()
        return runCatching { element.asString }.getOrNull()
    }

    private fun JsonObject.optStrings(key: String): List<String> {
        val element = get(key) ?: return emptyList()
        if (element.isJsonNull) return emptyList()
        return if (element.isJsonArray) {
            element.asJsonArray.mapNotNull {
                if (it.isJsonNull) null else runCatching { it.asString }.getOrNull()
            }
        } else {
            listOfNotNull(runCatching { element.asString }.getOrNull())
        }
    }

    private fun JsonObject.optInt(key: String): Int? {
        val element = get(key) ?: return null
        if (element.isJsonNull) return null
        return runCatching { element.asInt }.getOrNull()
    }

    private fun JsonObject.optArray(key: String): JsonArray? {
        val element = get(key) ?: return null
        if (element.isJsonNull) return null
        return element as? JsonArray
    }
}
