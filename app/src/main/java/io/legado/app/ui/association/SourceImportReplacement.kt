package io.legado.app.ui.association

import io.legado.app.data.entities.ReplaceRule
import io.legado.app.utils.replace

internal fun ReplaceRule.matchesSourceImport(name: String, url: String): Boolean {
    if (!isEnabled || !scopeSource) return false
    fun String.matchesSourceValue(): Boolean =
        (name.isNotBlank() && contains(name, ignoreCase = true)) ||
            (url.isNotBlank() && contains(url, ignoreCase = true))

    val included = scope.isNullOrEmpty() || scope.orEmpty().matchesSourceValue()
    val excluded = !excludeScope.isNullOrEmpty() && excludeScope.orEmpty().matchesSourceValue()
    return included && !excluded
}

internal fun applySourceImportReplacement(
    sourceJson: String,
    rule: ReplaceRule,
): String = if (rule.isRegex) {
    sourceJson.replace(
        rule.name,
        rule.regex,
        rule.replacement,
        rule.getValidTimeoutMillisecond(),
        includeContentInTimeoutMessage = false,
    )
} else {
    sourceJson.replace(rule.pattern, rule.replacement)
}
