package io.legado.app.web.mcp

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.model.CheckSourceResult
import io.legado.app.model.CheckSourceStatus

object McpFormat {

    const val TRUNCATE_LIMIT = 100_000

    private val prettyGson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create()

    fun detectFormat(source: String): String {
        val first = source.firstOrNull { !it.isWhitespace() && it != '\uFEFF' }
        return if (first == '{' || first == '[') "json" else "js"
    }

    fun summarizeSources(sources: List<BookSource>, search: String?): List<Map<String, Any>> {
        val summaries = sources.map { source ->
            mapOf(
                "bookSourceName" to source.bookSourceName,
                "bookSourceUrl" to source.bookSourceUrl,
                "bookSourceGroup" to source.bookSourceGroup.orEmpty(),
                "enabled" to source.enabled,
                "isJsSource" to source.isJsSource(),
            )
        }
        if (search.isNullOrEmpty()) return summaries
        return summaries.filter { summary ->
            (summary["bookSourceName"] as String).contains(search, ignoreCase = true) ||
                (summary["bookSourceUrl"] as String).contains(search, ignoreCase = true)
        }
    }

    fun toPrettyJson(value: Any): String = prettyGson.toJson(value)

    fun prettyJson(json: String): String = prettyGson.toJson(JsonParser.parseString(json))

    fun truncate(text: String, limit: Int = TRUNCATE_LIMIT): String {
        if (text.length <= limit) return text
        return text.take(limit) + "\n…[已截断,原文 ${text.length} 字符]"
    }

    fun renderCheckSummary(
        requestedSources: List<BookSourcePart>,
        results: Map<String, CheckSourceResult>,
        messages: Map<String, String>,
    ): String {
        val failed = mutableListOf<String>()
        val passed = mutableListOf<String>()
        val pending = mutableListOf<String>()
        requestedSources.forEach { requested ->
            val result = results[requested.bookSourceUrl]
            val message = messages[requested.bookSourceUrl].orEmpty()
            when (result?.status) {
                CheckSourceStatus.FAILED -> failed += renderCheckResult(requested, result)
                CheckSourceStatus.PASSED -> passed += renderCheckResult(requested, result)
                CheckSourceStatus.NOT_COMPLETED -> pending += renderCheckResult(requested, result)
                null -> {
                    val label = "${requested.bookSourceName}(${requested.bookSourceUrl})"
                    val detail = message.takeIf { it.isNotEmpty() }?.let { ":$it" }.orEmpty()
                    pending += "[未完成] $label$detail"
                }
            }
        }
        return buildString {
            appendLine("失败 ${failed.size}/${requestedSources.size}:")
            appendLinesOrEmpty(failed)
            appendLine()
            appendLine("通过 ${passed.size}/${requestedSources.size}:")
            appendLinesOrEmpty(passed)
            appendLine()
            appendLine("未完成 ${pending.size}/${requestedSources.size}:")
            appendLinesOrEmpty(pending)
        }.trimEnd()
    }

    fun renderCheckResult(source: BookSourcePart, result: CheckSourceResult): String {
        val state = when (result.status) {
            CheckSourceStatus.PASSED -> "通过"
            CheckSourceStatus.FAILED -> "失败"
            CheckSourceStatus.NOT_COMPLETED -> "未完成"
        }
        val detail = result.detail.takeIf { it.isNotEmpty() }?.let { ":$it" }.orEmpty()
        return "[$state] ${source.bookSourceName}(${source.bookSourceUrl})$detail"
    }

    private fun StringBuilder.appendLinesOrEmpty(lines: List<String>) {
        if (lines.isEmpty()) appendLine("(无)") else lines.forEach { appendLine(it) }
    }
}
