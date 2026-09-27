package io.legado.app.ui.association

import io.legado.app.constant.AppPattern
import io.legado.app.utils.splitNotBlank

internal fun matchesSourceImportSearch(
    query: String,
    name: String?,
    url: String?,
    group: String?,
    comment: String?,
): Boolean {
    if (query.isEmpty()) return true
    if (query.startsWith("group:")) {
        val wanted = query.substringAfter("group:").trim()
        return group?.splitNotBlank(AppPattern.splitGroupRegex)
            ?.any { it.trim() == wanted } == true
    }
    return name?.contains(query, ignoreCase = true) == true ||
        url?.contains(query, ignoreCase = true) == true ||
        group?.contains(query, ignoreCase = true) == true ||
        comment?.contains(query, ignoreCase = true) == true
}
