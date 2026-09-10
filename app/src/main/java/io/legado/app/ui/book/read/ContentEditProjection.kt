package io.legado.app.ui.book.read

import io.legado.app.constant.AppPattern

/** Keep image/review markup in the draft while editing the text around it. */
internal class ContentEditProjection(val raw: String) {
    private val images = buildList {
        val matcher = AppPattern.imgPattern.matcher(raw)
        while (matcher.find()) add(matcher.start() until matcher.end())
    }

    val text: String = buildString {
        var offset = 0
        images.forEach {
            append(raw, offset, it.first)
            offset = it.last + 1
        }
        append(raw, offset, raw.length)
    }

    fun displayOffset(rawOffset: Int): Int {
        val offset = rawOffset.coerceIn(0, raw.length)
        return offset - images.sumOf {
            (offset - it.first).coerceIn(0, it.last + 1 - it.first)
        }
    }

    fun rawOffset(displayOffset: Int, afterImages: Boolean = true): Int {
        var offset = displayOffset.coerceIn(0, text.length)
        for (image in images) {
            if (image.first > offset || (!afterImages && image.first == offset)) break
            offset += image.last + 1 - image.first
        }
        return offset
    }

    fun replace(start: Int, end: Int, replacement: String): String {
        require(start in 0..end && end <= text.length)
        val from = rawOffset(start)
        val to = rawOffset(end, afterImages = false).coerceAtLeast(from)
        return buildString {
            append(raw, 0, from)
            append(replacement)
            // Removing visible text must not silently delete the hidden images within it.
            images.filter { it.first >= from && it.last < to }.forEach {
                append(raw, it.first, it.last + 1)
            }
            append(raw, to, raw.length)
        }
    }
}
