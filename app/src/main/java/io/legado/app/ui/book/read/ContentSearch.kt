package io.legado.app.ui.book.read

import java.util.regex.Pattern

internal fun findContentMatches(
    text: String,
    query: String,
    regex: Boolean,
    matchCase: Boolean,
    checkActive: () -> Unit = {},
): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val flags = (if (regex) 0 else Pattern.LITERAL) or
        (if (matchCase) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
    val matcher = Pattern.compile(query, flags).matcher(text)
    return buildList {
        checkActive()
        while (matcher.find()) {
            checkActive()
            add(matcher.start() until matcher.end())
        }
    }
}

internal fun cycleContentMatchIndex(index: Int, direction: Int, count: Int): Int {
    if (count == 0) return -1
    return ((index + direction) % count + count) % count
}
