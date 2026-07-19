package io.legado.app.ui.association

import io.legado.app.data.entities.ReplaceRule

// Keep enough headroom below SQLite's host parameter limit.
internal const val REPLACE_RULE_QUERY_CHUNK_SIZE = 900

internal data class ReplaceRuleImportComparison(
    val existingRules: List<ReplaceRule?>,
    val selectStatus: List<Boolean>,
)

internal fun compareImportedReplaceRules(
    importedRules: List<ReplaceRule>,
    loadExistingRules: (List<Long>) -> List<ReplaceRule>,
): ReplaceRuleImportComparison {
    val existingRulesById = importedRules
        .map { it.id }
        .distinct()
        .chunked(REPLACE_RULE_QUERY_CHUNK_SIZE)
        .flatMap(loadExistingRules)
        .associateBy { it.id }
    val existingRules = importedRules.map { existingRulesById[it.id] }
    return ReplaceRuleImportComparison(
        existingRules = existingRules,
        selectStatus = existingRules.map { it == null },
    )
}
