package io.legado.app.help

internal fun findTextRanges(text: String, query: String): List<IntRange> {
    if (query.isBlank()) return emptyList()
    return buildList {
        var startIndex = 0
        while (startIndex < text.length) {
            val index = text.indexOf(query, startIndex, ignoreCase = true)
            if (index < 0) break
            add(index until index + query.length)
            startIndex = index + query.length
        }
    }
}
