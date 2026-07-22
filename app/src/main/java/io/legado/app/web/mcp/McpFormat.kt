package io.legado.app.web.mcp

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.legado.app.data.entities.BookSource

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
}
