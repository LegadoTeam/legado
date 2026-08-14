package io.legado.app.ui.book.info

/**
 * Drop stale BookInfo network callbacks after the detail page switches books.
 */
internal object BookInfoLoadGuard {

    fun shouldApply(
        currentGeneration: Long,
        callbackGeneration: Long,
        currentBookUrl: String?,
        requestedBookUrl: String,
    ): Boolean {
        if (callbackGeneration != currentGeneration) return false
        val current = currentBookUrl ?: return false
        return current == requestedBookUrl
    }
}
