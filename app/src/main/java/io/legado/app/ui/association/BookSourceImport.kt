package io.legado.app.ui.association

import com.google.gson.JsonObject
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.replace
import kotlinx.coroutines.CancellationException

internal sealed interface BookSourceImportJson {
    data class Sources(val items: List<BookSource>) : BookSourceImportJson
    data class SourceUrls(val items: List<String>) : BookSourceImportJson
}

internal data class BookSourceImportCandidate(
    val original: BookSource,
    val originalJson: String,
    val replaced: BookSource? = null,
    val replacedJson: String? = null,
    val replacementError: String? = null,
) {
    fun source(useReplacement: Boolean): BookSource =
        if (useReplacement) replaced ?: original else original

    fun canImport(useReplacement: Boolean): Boolean =
        !useReplacement || replacementError == null
}

internal fun prepareBookSourceImportCandidate(
    source: BookSource,
    rules: List<ReplaceRule>,
): BookSourceImportCandidate {
    val originalJson = GSON.toJson(source)
    val sourceName = source.bookSourceName.orEmpty()
    val sourceUrl = source.bookSourceUrl.orEmpty()
    val matchingRules = rules.filter {
        it.pattern.isNotEmpty() && it.matchesSource(sourceName, sourceUrl)
    }
    if (matchingRules.isEmpty()) {
        return BookSourceImportCandidate(source, originalJson, source)
    }

    var replacedJson = originalJson
    try {
        matchingRules.forEach { rule ->
            replacedJson = if (rule.isRegex) {
                replacedJson.replace(
                    rule.name,
                    rule.regex,
                    rule.replacement,
                    rule.getValidTimeoutMillisecond(),
                    includeContentInTimeoutMessage = false,
                )
            } else {
                replacedJson.replace(rule.pattern, rule.replacement)
            }
        }
        val replaced = (parseBookSourceJson(replacedJson, allowSourceUrls = false)
                as BookSourceImportJson.Sources).items.single()
        return BookSourceImportCandidate(source, originalJson, replaced, replacedJson)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        return BookSourceImportCandidate(
            source,
            originalJson,
            replacedJson = replacedJson,
            replacementError = error.localizedMessage ?: error.javaClass.simpleName,
        )
    }
}

private fun ReplaceRule.matchesSource(name: String, url: String): Boolean {
    if (!isEnabled || !scopeSource) return false
    fun String.matchesSourceValue(): Boolean =
        (name.isNotBlank() && contains(name, ignoreCase = true)) ||
            (url.isNotBlank() && contains(url, ignoreCase = true))

    val included = scope.isNullOrEmpty() || scope.orEmpty().matchesSourceValue()
    val excluded = !excludeScope.isNullOrEmpty() && excludeScope.orEmpty().matchesSourceValue()
    return included && !excluded
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
