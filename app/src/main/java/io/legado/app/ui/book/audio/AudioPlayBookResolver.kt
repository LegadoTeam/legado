package io.legado.app.ui.book.audio

internal fun shouldReuseCurrentAudioPlay(
    requestedBookUrl: String?,
    currentBookUrl: String?,
): Boolean = !currentBookUrl.isNullOrBlank() &&
        (requestedBookUrl.isNullOrBlank() || requestedBookUrl == currentBookUrl)

internal fun <T> resolveAudioPlayBook(
    requestedBookUrl: String?,
    cachedBook: T?,
    bookUrlOf: (T) -> String,
    findBook: (String) -> T?,
): T? {
    val targetBookUrl = requestedBookUrl?.takeIf { it.isNotBlank() }
        ?: return cachedBook?.takeIf { bookUrlOf(it).isNotBlank() }
    cachedBook?.takeIf { bookUrlOf(it) == targetBookUrl }?.let { return it }
    return findBook(targetBookUrl)?.takeIf { bookUrlOf(it) == targetBookUrl }
}
