package io.legado.app.ui.book.toc.rule

import io.legado.app.data.entities.TxtTocRule

internal fun List<TxtTocRule>.filterByKeyword(keyword: String): List<TxtTocRule> {
    val key = keyword.trim()
    if (key.isEmpty()) return this
    return filter { rule ->
        rule.name.contains(key, ignoreCase = true) ||
            rule.example?.contains(key, ignoreCase = true) == true
    }
}
