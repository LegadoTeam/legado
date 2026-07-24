package io.legado.app.ui.association

import com.google.gson.JsonObject
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject

internal sealed interface BookSourceImportJson {
    data class Sources(val items: List<BookSource>) : BookSourceImportJson
    data class SourceUrls(val items: List<String>) : BookSourceImportJson
}

internal fun parseBookSourceJson(
    text: String,
    allowSourceUrls: Boolean = true,
): BookSourceImportJson {
    val json = text.trim()
    return when {
        json.isJsonArray() -> {
            val sources = GSON.fromJsonArray<BookSource>(json).getOrThrow()
            sources.forEach { it.requireBookSourceUrl() }
            BookSourceImportJson.Sources(sources)
        }

        json.isJsonObject() -> {
            val jsonObject = GSON.fromJsonObject<JsonObject>(json).getOrThrow()
            if (jsonObject.has("sourceUrls")) {
                if (!allowSourceUrls) {
                    throw NoStackTraceException("不是书源")
                }
                val sourceUrlsElement = jsonObject.get("sourceUrls")
                if (sourceUrlsElement?.isJsonNull == true) {
                    throw NoStackTraceException("不是书源")
                }
                val sourceUrls = sourceUrlsElement
                    ?.let { GSON.fromJsonArray<String>(it.toString()).getOrThrow() }
                    .orEmpty()
                if (sourceUrls.any { it.isBlank() }) {
                    throw NoStackTraceException("不是书源")
                }
                BookSourceImportJson.SourceUrls(sourceUrls)
            } else {
                val source = GSON.fromJsonObject<BookSource>(json).getOrThrow()
                source.requireBookSourceUrl()
                BookSourceImportJson.Sources(listOf(source))
            }
        }

        else -> throw NoStackTraceException("不是书源")
    }
}

private fun BookSource.requireBookSourceUrl() {
    if (bookSourceUrl.isNullOrBlank()) {
        throw NoStackTraceException("不是书源")
    }
}
