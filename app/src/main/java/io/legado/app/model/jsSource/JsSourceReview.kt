package io.legado.app.model.jsSource

import androidx.collection.LruCache
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.model.analyzeRule.ReviewRuleParser
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import java.util.ArrayDeque
import kotlin.coroutines.coroutineContext

internal object JsSourceReview {

    private val capabilityCache = LruCache<String, Boolean>(64)

    fun rememberReviewCapability(source: BookSource, enabled: Boolean) {
        capabilityCache.put(capabilityKey(source), enabled)
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
        val content = item.optString("content")?.takeIf { it.isNotBlank() } ?: return null
        return ReviewRuleParser.DetailItem(
            id = item.optString("id"),
            avatar = item.optString("avatar")?.let { NetworkUtils.getAbsoluteURL(baseUrl, it) },
            name = item.optString("name"),
            badges = item.optString("badge")
                ?.takeIf { it.isNotBlank() }
                ?.let(::listOf)
                .orEmpty(),
            content = content,
            imageUrl = null,
            audioUrl = null,
            time = null,
            likeCount = null,
            replyCount = null,
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
