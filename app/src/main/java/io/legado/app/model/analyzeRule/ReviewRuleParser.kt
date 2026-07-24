package io.legado.app.model.analyzeRule

import com.jayway.jsonpath.PathNotFoundException
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.rule.ReviewRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setChapter
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isDataUrl
import org.htmlunit.corejs.javascript.NativeArray
import org.htmlunit.corejs.javascript.Scriptable
import kotlin.coroutines.CoroutineContext

internal object ReviewRuleParser {

    internal data class SummaryResult(
        val counts: Map<Int, Int>,
        val keys: Map<Int, String>,
    )

    internal data class DetailPage(
        val items: List<DetailItem>,
        val nextPageUrl: String?,
    )

    internal data class DetailItem(
        val id: String?,
        val avatar: String?,
        val name: String?,
        val badges: List<String>,
        val content: String?,
        val imageUrl: String?,
        val audioUrl: String?,
        val time: String?,
        val likeCount: Int?,
        val replyCount: Int?,
        val replies: List<DetailItem>,
    )

    internal fun parseSummary(
        body: String,
        rule: ReviewRule,
        source: BaseSource,
        book: Book,
        chapter: BookChapter,
        baseUrl: String,
        context: CoroutineContext,
    ): SummaryResult? {
        if (body.isBlank()) return null
        val listRule = rule.summaryListRule?.trim().orEmpty()
        val indexRule = rule.summaryParagraphIndexRule?.trim().orEmpty()
        if (listRule.isEmpty() || indexRule.isEmpty()) return null

        val analyzeRule = AnalyzeRule(book, source)
            .setChapter(chapter)
            .setCoroutineContext(context)
            .setContent(body, baseUrl)
        val loggedRules = hashSetOf<String>()
        val items = getElementList(
            analyzeRule,
            listRule,
            "段评统计列表规则执行出错",
            loggedRules,
        )
        if (items.isEmpty()) return SummaryResult(emptyMap(), emptyMap())

        val counts = HashMap<Int, Int>()
        val keys = HashMap<Int, String>()
        val countRule = rule.summaryCountRule?.trim().orEmpty()
        val dataRule = rule.summaryParagraphDataRule?.trim().orEmpty()
        val itemRule = AnalyzeRule(book, source)
            .setChapter(chapter)
            .setCoroutineContext(context)

        items.forEachIndexed { index, item ->
            itemRule.setContent(item, baseUrl)
            val indexValue = safeRuleString(itemRule, indexRule, item, loggedRules)
            val paragraphIndex = parseInt(indexValue) ?: index + 1
            val count = if (countRule.isEmpty()) {
                0
            } else {
                parseInt(safeRuleString(itemRule, countRule, item, loggedRules)) ?: 0
            }
            if (paragraphIndex != 0 && count > 0) {
                counts[paragraphIndex] = count
                keys[paragraphIndex] = safeRuleString(itemRule, dataRule, item, loggedRules)
                    ?: indexValue
                    ?: paragraphIndex.toString()
            }
        }
        return SummaryResult(counts, keys)
    }

    internal fun parseDetailPage(
        body: String,
        rule: ReviewRule,
        nextPageRule: String?,
        baseUrl: String,
        source: BaseSource,
        book: Book,
        chapter: BookChapter,
        context: CoroutineContext,
        paraIndex: String,
        paraData: String,
        page: String,
    ): DetailPage {
        val listRule = rule.detailListRule?.trim().orEmpty()
        if (listRule.isEmpty()) return DetailPage(emptyList(), null)

        val analyzeRule = AnalyzeRule(book, source)
            .setChapter(chapter)
            .setCoroutineContext(context)
            .setContent(body, baseUrl)
            .setLocal("paraIndex", paraIndex)
            .setLocal("paraData", paraData)
            .setLocal("page", page)
        val loggedRules = hashSetOf<String>()
        val items = getElementList(
            analyzeRule,
            listRule,
            "段评详情列表规则执行出错",
            loggedRules,
        )
        val nextPageUrl = nextPageRule?.takeIf { it.isNotBlank() }
            ?.let { safeRuleString(analyzeRule, it, loggedRules = loggedRules) }
            ?.trim()
            ?.let { value ->
                if (AnalyzeUrl.paramPattern.matcher(value).find()) {
                    value
                } else {
                    NetworkUtils.getAbsoluteURL(baseUrl, value)
                }
            }
        return DetailPage(
            items = items.mapNotNull {
                parseDetailItem(
                    analyzeRule,
                    it,
                    rule,
                    baseUrl,
                    isReply = false,
                    loggedRules = loggedRules,
                )
            },
            nextPageUrl = nextPageUrl,
        )
    }

    private fun parseDetailItem(
        analyzeRule: AnalyzeRule,
        item: Any,
        rule: ReviewRule,
        baseUrl: String,
        isReply: Boolean,
        loggedRules: MutableSet<String>,
    ): DetailItem? {
        analyzeRule.setContent(item, baseUrl)
        val idRule = if (isReply) rule.replyIdRule else rule.detailIdRule
        val avatarRule = if (isReply) rule.replyAvatarRule else rule.detailAvatarRule
        val nameRule = if (isReply) rule.replyNameRule else rule.detailNameRule
        val badgeRule = if (isReply) rule.replyBadgeRule else rule.detailBadgeRule
        val contentRule = if (isReply) rule.replyContentRule else rule.detailContentRule

        val id = safeRuleString(analyzeRule, idRule, item, loggedRules)
        val avatar = safeRuleString(analyzeRule, avatarRule, item, loggedRules)?.let {
            NetworkUtils.getAbsoluteURL(baseUrl, it)
        }
        val name = safeRuleString(analyzeRule, nameRule, item, loggedRules)
        val badges = safeRuleList(analyzeRule, badgeRule, item, loggedRules)
        val rawContent = safeRuleString(analyzeRule, contentRule, item, loggedRules)
        val protocol = parseContentProtocol(rawContent, baseUrl)
        val content = protocol?.text ?: if (protocol == null) rawContent else ""
        val replies = if (!isReply && !rule.replyListRule.isNullOrBlank()) {
            getElementList(
                analyzeRule,
                rule.replyListRule!!.trim(),
                "段评回复列表规则执行出错",
                loggedRules,
            ).mapNotNull {
                parseDetailItem(
                    analyzeRule,
                    it,
                    rule,
                    baseUrl,
                    isReply = true,
                    loggedRules = loggedRules,
                )
            }
        } else {
            emptyList()
        }

        if (name.isNullOrBlank() && content.isNullOrBlank() &&
            protocol?.imageUrl.isNullOrBlank() && protocol?.audioUrl.isNullOrBlank()
        ) {
            return null
        }
        return DetailItem(
            id = id,
            avatar = avatar,
            name = name,
            badges = badges,
            content = content,
            imageUrl = protocol?.imageUrl,
            audioUrl = protocol?.audioUrl,
            time = protocol?.time,
            likeCount = if (isReply) null else protocol?.likeCount,
            replyCount = if (isReply) null else protocol?.replyCount,
            replies = replies,
        )
    }

    private fun getElementList(
        analyzeRule: AnalyzeRule,
        rule: String,
        errorMessage: String,
        loggedRules: MutableSet<String>,
    ): List<Any> {
        return runCatching { normalizeList(analyzeRule.getElementsRaw(rule)) }
            .onFailure { logRuleErrorOnce(loggedRules, errorMessage, rule, it) }
            .getOrDefault(emptyList())
    }

    private fun normalizeList(value: Any?): List<Any> {
        return when (value) {
            is NativeArray -> buildList {
                for (index in 0 until value.length.toInt()) {
                    val item = value.get(index, value)
                    if (item != null && item !== Scriptable.NOT_FOUND) add(item)
                }
            }

            is List<*> -> value.mapNotNull { item ->
                item?.takeUnless { it === Scriptable.NOT_FOUND }
            }

            is Array<*> -> value.mapNotNull { item ->
                item?.takeUnless { it === Scriptable.NOT_FOUND }
            }

            is String -> GSON.fromJsonArray<Any>(value).getOrNull().orEmpty()
            else -> emptyList()
        }
    }

    private data class ContentProtocol(
        val text: String?,
        val imageUrl: String?,
        val audioUrl: String?,
        val time: String?,
        val likeCount: Int?,
        val replyCount: Int?,
    )

    private fun parseContentProtocol(raw: String?, baseUrl: String): ContentProtocol? {
        val value = raw?.trim().orEmpty()
        if (!value.startsWith("{") || !value.endsWith("}")) return null
        val content = GSON.fromJsonObject<Map<String, Any?>>(value).getOrNull() ?: return null
        val text = content.stringValue("text")
        val image = content.stringValue("img")
        val audio = content.stringValue("audio")
        val time = content.stringValue("time")
        val likeCount = parseInt(content["likeCount"])
        val replyCount = parseInt(content["replyCount"])
        if (text == null && image == null && audio == null && time == null &&
            likeCount == null && replyCount == null
        ) {
            return null
        }
        return ContentProtocol(
            text = text,
            imageUrl = image?.let { NetworkUtils.getAbsoluteURL(baseUrl, it) },
            audioUrl = audio?.let { NetworkUtils.getAbsoluteURL(baseUrl, it) },
            time = time,
            likeCount = likeCount,
            replyCount = replyCount,
        )
    }

    private fun Map<String, Any?>.stringValue(key: String): String? {
        return get(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun parseInt(value: Any?): Int? {
        return when (value) {
            null -> null
            is Number -> value.toInt()
            else -> value.toString().trim().let {
                it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt()
            }
        }
    }

    private fun safeRuleString(
        analyzeRule: AnalyzeRule,
        rule: String?,
        content: Any? = null,
        loggedRules: MutableSet<String>,
    ): String? {
        val value = rule?.trim().orEmpty()
        if (value.isEmpty()) return null
        val result = if (content is Map<*, *> && isJsonPath(value)) {
            runCatching { AnalyzeByJSonPath(content) { throw it }.getString(value) }
        } else {
            runCatching {
                analyzeRule.getString(analyzeRule.splitSourceRule(value), content)
            }
        }
        result.onFailure {
            if (it !is PathNotFoundException) {
                logRuleErrorOnce(loggedRules, "段评规则执行出错", value, it)
            }
        }
        return result.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun safeRuleList(
        analyzeRule: AnalyzeRule,
        rule: String?,
        content: Any? = null,
        loggedRules: MutableSet<String>,
    ): List<String> {
        val value = rule?.trim().orEmpty()
        if (value.isEmpty()) return emptyList()
        val result = if (content is Map<*, *> && isJsonPath(value)) {
            runCatching { AnalyzeByJSonPath(content) { throw it }.getStringList(value) }
        } else {
            runCatching { analyzeRule.getStringList(value, content).orEmpty() }
        }
        if (result.exceptionOrNull() is PathNotFoundException) return emptyList()
        val list = result
            .onFailure { logRuleErrorOnce(loggedRules, "段评规则执行出错", value, it) }
            .getOrDefault(emptyList())
        if (list.isNotEmpty()) return list.flatMap(::splitBadgeValue).distinct()
        return splitBadgeValue(safeRuleString(analyzeRule, value, content, loggedRules)).distinct()
    }

    private fun isJsonPath(rule: String): Boolean =
        rule.startsWith("$.") || rule.startsWith("$[")

    private fun logRuleError(message: String, rule: String, error: Throwable) {
        AppLog.put("$message: $rule\n${error.localizedMessage}", error)
    }

    private fun logRuleErrorOnce(
        loggedRules: MutableSet<String>,
        message: String,
        rule: String,
        error: Throwable,
    ) {
        val key = "$rule\u0000${error::class.qualifiedName}\u0000${error.message}"
        if (loggedRules.add(key)) logRuleError(message, rule, error)
    }

    private fun splitBadgeValue(value: String?): List<String> {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        if (raw.isDataUrl()) return listOf(raw)
        if (raw.startsWith("[") && raw.endsWith("]")) {
            GSON.fromJsonArray<String>(raw).getOrNull()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        val separator = when {
            '\n' in raw -> '\n'
            '|' in raw -> '|'
            ',' in raw && shouldSplitByComma(raw) -> ','
            else -> return listOf(raw)
        }
        return raw.split(separator).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun shouldSplitByComma(value: String): Boolean {
        if (value.startsWith("data:")) return false
        if (!value.contains("://")) return true
        return value.contains(",http://") || value.contains(",https://")
    }
}
