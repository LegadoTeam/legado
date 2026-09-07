package io.legado.app.help

internal data class HelpSection(
    val title: String,
    val markdown: String,
    val depth: Int = 0,
)

internal fun parseHelpSections(markdown: String): List<HelpSection> {
    val lines = markdown.lines()
    val parentSections = splitHelpSections(lines, 2)
        ?: return splitHelpSections(lines, 3).orEmpty()
    return parentSections.flatMap { parent ->
        listOf(parent) + splitHelpSections(parent.markdown.lines(), 3)
            .orEmpty()
            .map { it.copy(depth = 1) }
    }
}

private fun splitHelpSections(lines: List<String>, level: Int): List<HelpSection>? {
    val headings = findHelpHeadings(lines, level)
    if (headings.size < 2) return null
    return headings.mapIndexed { index, heading ->
        val end = headings.getOrNull(index + 1)?.first ?: lines.size
        HelpSection(
            title = heading.second,
            markdown = lines.subList(heading.first, end).joinToString("\n").trimEnd(),
        )
    }
}

private fun findHelpHeadings(lines: List<String>, level: Int): List<Pair<Int, String>> {
    val headings = mutableListOf<Pair<Int, String>>()
    var openFence: FenceMarker? = null
    lines.forEachIndexed { index, line ->
        val marker = fenceMarker(line)
        if (openFence == null && marker != null) {
            openFence = marker
            return@forEachIndexed
        }
        if (openFence?.isClosedBy(marker) == true) {
            openFence = null
            return@forEachIndexed
        }
        if (openFence == null) {
            headingTitle(line, level)?.let { headings.add(index to it) }
        }
    }
    return headings
}

private data class FenceMarker(
    val character: Char,
    val length: Int,
    val trailing: String,
) {
    fun isClosedBy(marker: FenceMarker?): Boolean {
        return marker != null &&
            marker.character == character &&
            marker.length >= length &&
            marker.trailing.isBlank()
    }
}

private fun fenceMarker(line: String): FenceMarker? {
    val trimmed = line.trimStart(' ')
    if (line.length - trimmed.length > 3) return null
    val marker = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
    val length = trimmed.takeWhile { it == marker }.length
    if (length < 3) return null
    return FenceMarker(marker, length, trimmed.substring(length))
}

private fun headingTitle(line: String, level: Int): String? {
    val trimmed = line.trimStart(' ')
    if (line.length - trimmed.length > 3) return null
    val prefix = "#".repeat(level) + " "
    return trimmed.takeIf { it.startsWith(prefix) }
        ?.removePrefix(prefix)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
