package io.legado.app.ui.book.read.page.provider

internal object ChapterTitleParser {

    private const val NUMBER = "[零〇一二三四五六七八九十百千万亿两0-9]+"
    private val prefix = Regex(
        "^((?:第${NUMBER}卷[\\s\\p{Zs}]*)?第${NUMBER}[章节回]|番外${NUMBER})" +
            "[\\s\\p{Zs}]+(.+)$"
    )

    fun split(title: String, enabled: Boolean, isVolume: Boolean): Pair<String, String>? {
        if (!enabled || isVolume || '\n' in title || '\r' in title) return null
        val match = prefix.matchEntire(title) ?: return null
        val name = match.groupValues[2].trim()
        return name.takeIf { it.isNotEmpty() }?.let {
            match.groupValues[1].trim() to it
        }
    }
}
