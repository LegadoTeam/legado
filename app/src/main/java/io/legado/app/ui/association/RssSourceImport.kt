package io.legado.app.ui.association

import com.google.gson.JsonObject
import io.legado.app.data.entities.RssSource
import io.legado.app.exception.NoStackTraceException
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
            sources.firstOrNull()?.requireSourceUrl()
            RssSourceImportJson.Sources(sources)
        }

        json.isJsonObject() -> {
            val jsonObject = GSON.fromJsonObject<JsonObject>(json).getOrThrow()
            if (jsonObject.has("sourceUrls")) {
                val sourceUrls = jsonObject.get("sourceUrls")
                    ?.takeUnless { it.isJsonNull }
                    ?.let { GSON.fromJsonArray<String>(it.toString()).getOrThrow() }
                    .orEmpty()
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

private fun RssSource.requireSourceUrl() {
    if (sourceUrl.isNullOrEmpty()) {
        throw NoStackTraceException("不是订阅源")
    }
}
