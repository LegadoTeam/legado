package io.legado.app.ui.association

import com.google.gson.JsonObject
import io.legado.app.data.entities.RssSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.source.requireSourceUrl
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject

internal sealed interface RssSourceImportJson {
    data class Sources(val items: List<RssSource>) : RssSourceImportJson
    data class SourceUrls(val items: List<String>) : RssSourceImportJson
}

internal fun parseRssSourceJson(text: String): RssSourceImportJson {
    val json = text.trim()
    return when {
        json.isJsonArray() -> {
            val sources = GSON.fromJsonArray<RssSource>(json).getOrThrow()
            sources.forEach { it.requireSourceUrl() }
            RssSourceImportJson.Sources(sources)
        }

        json.isJsonObject() -> {
            val jsonObject = GSON.fromJsonObject<JsonObject>(json).getOrThrow()
            if (jsonObject.has("sourceUrls")) {
                val sourceUrlsElement = jsonObject.get("sourceUrls")
                if (sourceUrlsElement?.isJsonNull == true) {
                    throw NoStackTraceException("不是订阅源")
                }
                val sourceUrls = sourceUrlsElement
                    ?.let { GSON.fromJsonArray<String>(it.toString()).getOrThrow() }
                    .orEmpty()
                if (sourceUrls.any { it.isBlank() }) {
                    throw NoStackTraceException("不是订阅源")
                }
                RssSourceImportJson.SourceUrls(sourceUrls)
            } else {
                val source = GSON.fromJsonObject<RssSource>(json).getOrThrow()
                source.requireSourceUrl()
                RssSourceImportJson.Sources(listOf(source))
            }
        }

        else -> throw NoStackTraceException("不是订阅源")
    }
}
