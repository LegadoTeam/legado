package io.legado.app.ui.association

import com.google.gson.JsonObject
import io.legado.app.data.entities.ReplaceRule
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

internal fun parseSingleRssSourceJson(text: String): RssSource {
    val json = text.trim()
    return when {
        json.isJsonObject() -> GSON.fromJsonObject<RssSource>(json).getOrThrow()
        json.isJsonArray() -> GSON.fromJsonArray<RssSource>(json).getOrThrow().singleOrNull()
            ?: throw NoStackTraceException("不是单个订阅源")
        else -> throw NoStackTraceException("不是单个订阅源")
    }
}

internal data class RssSourceImportCandidate(
    val original: RssSource,
    val originalJson: String,
    val replaced: RssSource? = null,
    val replacedJson: String? = null,
    val replacementError: String? = null,
) {
    fun source(useReplacement: Boolean): RssSource =
        if (useReplacement) replaced ?: original else original

    fun canImport(useReplacement: Boolean): Boolean =
        !useReplacement || replacementError == null
}

internal fun prepareRssSourceImportCandidate(
    source: RssSource,
    rules: List<ReplaceRule>,
): RssSourceImportCandidate {
    val originalJson = GSON.toJson(source)
    val matchingRules = rules.filter {
        it.pattern.isNotEmpty() &&
            it.matchesSourceImport(source.sourceName, source.sourceUrl)
    }
    if (matchingRules.isEmpty()) {
        return RssSourceImportCandidate(source, originalJson, source)
    }

    var replacedJson = originalJson
    try {
        matchingRules.forEach { rule ->
            replacedJson = applySourceImportReplacement(replacedJson, rule)
        }
        val replaced = parseSingleRssSourceJson(replacedJson).also { it.requireSourceUrl() }
        return RssSourceImportCandidate(
            source,
            originalJson,
            replaced,
            replacedJson,
        )
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        return RssSourceImportCandidate(
            source,
            originalJson,
            replacedJson = replacedJson,
            replacementError = error.localizedMessage ?: error.javaClass.simpleName,
        )
    }
}

internal fun refreshRssSourceImportCandidates(
    candidates: List<RssSourceImportCandidate>,
    editedIndex: Int,
    editedSource: RssSource?,
    rules: List<ReplaceRule>,
): List<RssSourceImportCandidate> {
    require(editedIndex in candidates.indices)
    return candidates.mapIndexed { index, candidate ->
        prepareRssSourceImportCandidate(
            if (index == editedIndex) editedSource ?: candidate.original else candidate.original,
            rules,
        )
    }
}
