package io.legado.app.help

/** Manual highlight positions after chapter text changes. */
object HighlightAnchor {

    data class Anchor(val start: Int, val end: Int)

    fun reanchor(text: String, start: Int, end: Int, bookText: String): Anchor? {
        // Image and HTML selections can use a different display-text width.
        if (bookText.isEmpty() || end - start != bookText.length) return Anchor(start, end)
        if (text.isEmpty()) return null
        if (start in 0..text.length - bookText.length && text.startsWith(bookText, start)) {
            return Anchor(start, start + bookText.length)
        }
        val hit = text.indexOf(bookText)
        if (hit < 0) return null
        // Moving between repeated phrases is unsafe without surrounding context.
        if (text.indexOf(bookText, hit + 1) >= 0) return Anchor(start, end)
        return Anchor(hit, hit + bookText.length)
    }

    fun jumpPos(text: String, start: Int, bookText: String): Int {
        if (bookText.isEmpty()) return start
        return reanchor(text, start, start + bookText.length, bookText)?.start ?: start
    }
}
