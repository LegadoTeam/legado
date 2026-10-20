package io.legado.app.ui.book.searchContent

import io.legado.app.ui.book.read.page.provider.ChapterTitleParser
import io.legado.app.utils.splitNotBlank
import java.security.MessageDigest

enum class ContentSearchScope { ALL, DOWN, UP }

internal data class ContentSearchOptions(
    val scope: ContentSearchScope,
    val replace: Boolean,
    val regex: Boolean,
    val splitTitle: Boolean = false,
    val hideTitle: Boolean = false,
)

/** Directional coordinates use the same title lines as TextChapterLayout; ALL is unchanged. */
internal fun directionalContentSearchText(
    content: String,
    titleLength: Int,
    displayTitle: String,
    splitTitle: Boolean,
    hideTitle: Boolean,
    isVolume: Boolean,
    paragraphIndent: String,
): Pair<String, Int> {
    val lines = if (!hideTitle || isVolume || content.length == titleLength) {
        ChapterTitleParser.split(displayTitle, splitTitle, isVolume)?.let {
            listOf(it.first, it.second)
        } ?: displayTitle.splitNotBlank("\n").toList()
    } else emptyList()
    val prefix = if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")
    return buildString(prefix.length + content.length - titleLength) {
        append(prefix)
        // With an empty title, the legacy search processor omits the first body indent.
        if (titleLength == 0 && content.isNotEmpty()) append(paragraphIndent)
        append(content, titleLength, content.length)
    } to prefix.length
}

/** Holds at most 96 context characters, never a chapter in IntentData or a result list. */
data class ContentSearchAnchor internal constructor(
    val chapterIndex: Int,
    val inTitle: Boolean,
    val position: Int,
    val sourceLength: Int,
    val sourceDigest: List<Byte>,
    val before: String,
    val after: String,
) {
    fun resolve(text: String, titleLength: Int): Int? =
        ContentSearchText(chapterIndex, text, titleLength).resolve(this)

    companion object {
        fun capture(chapterIndex: Int, text: String, titleLength: Int, position: Int): ContentSearchAnchor? =
            ContentSearchText(chapterIndex, text, titleLength).anchor(position)
    }
}

/** Fingerprint each section once even when a large chapter has thousands of hits. */
internal class ContentSearchText(
    private val chapterIndex: Int,
    private val text: String,
    private val titleLength: Int,
) {
    private val valid = titleLength in 0..text.length
    // Layout appends a final paragraph separator; the search processor does not.
    private val bodyEnd = if (text.length > titleLength && text.endsWith('\n')) text.length - 1 else text.length
    private val titleDigest by lazy { digest(0, titleLength) }
    private val bodyDigest by lazy { digest(titleLength, bodyEnd) }

    fun anchor(position: Int): ContentSearchAnchor? {
        if (!valid || position !in 0..text.length) return null
        val inTitle = position < titleLength
        val start = if (inTitle) 0 else titleLength
        val end = if (inTitle) titleLength else bodyEnd
        val boundedPosition = position.coerceAtMost(end)
        return ContentSearchAnchor(
            chapterIndex, inTitle, boundedPosition - start, end - start,
            if (inTitle) titleDigest else bodyDigest,
            text.substring((boundedPosition - 32).coerceAtLeast(start), boundedPosition),
            text.substring(boundedPosition, (boundedPosition + 64).coerceAtMost(end)),
        )
    }

    fun resolve(anchor: ContentSearchAnchor): Int? {
        if (!valid || anchor.chapterIndex != chapterIndex) return null
        val offset = if (anchor.inTitle) 0 else titleLength
        val end = if (anchor.inTitle) titleLength else bodyEnd
        if (end - offset == anchor.sourceLength &&
            (if (anchor.inTitle) titleDigest else bodyDigest) == anchor.sourceDigest
        ) return offset + anchor.position
        val context = anchor.before + anchor.after
        if (context.isEmpty()) return null
        val first = text.indexOf(context, offset)
        if (first < 0 || first + context.length > end) return null
        // Changed text must have a unique match; guessing would move across repeated passages.
        val second = text.indexOf(context, first + 1)
        if (second >= 0 && second + context.length <= end) return null
        return first + anchor.before.length
    }

    private fun digest(start: Int, end: Int): List<Byte> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(4096)
        var size = 0
        // Hash UTF-16 code units in chunks without allocating another chapter-sized byte array.
        for (index in start until end) {
            val char = text[index].code
            buffer[size++] = (char ushr 8).toByte()
            buffer[size++] = char.toByte()
            if (size == buffer.size) {
                digest.update(buffer)
                size = 0
            }
        }
        digest.update(buffer, 0, size)
        return digest.digest().toList()
    }
}

internal fun selectContentSearchResults(
    results: List<SearchResult>,
    scope: ContentSearchScope,
    anchorPosition: Int? = null,
): List<SearchResult> {
    val selected = if (anchorPosition == null || scope == ContentSearchScope.ALL) results else {
        results.filter {
            if (scope == ContentSearchScope.UP) it.queryIndexInChapter <= anchorPosition
            else it.queryIndexInChapter >= anchorPosition
        }
    }
    // Preserve the original within-chapter ordinal used by the existing global-search reader.
    return if (scope == ContentSearchScope.UP) selected.asReversed() else selected
}

internal class ContentSearchAnchorException : Exception()

internal fun resolveContentSearchMatch(result: SearchResult, text: String, titleLength: Int): IntRange? {
    val position = result.contentAnchor?.resolve(text, titleLength) ?: return null
    // Match against the same body end as the anchor, without the layout-only separator.
    val content = if (text.length > titleLength) text.removeSuffix("\n") else text
    val length = if (result.isRegex) {
        val match = Regex(result.query).find(content, position)?.takeIf { it.range.first == position }
            ?: return null
        match.value.length
    } else {
        if (!content.startsWith(result.query, position)) return null
        result.query.length
    }
    return position until position + length
}
