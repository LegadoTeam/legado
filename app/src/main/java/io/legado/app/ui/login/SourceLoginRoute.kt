package io.legado.app.ui.login

import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource

internal data class SourceLoginRoute(
    val type: String,
    val key: String,
    val bookType: Int? = null,
)

internal fun resolveLoginSource(
    origin: String?,
    currentSource: BaseSource,
    findBookSource: (String) -> BookSource?,
    findRssSource: (String) -> RssSource?,
): BaseSource? {
    val sourceKey = origin?.trim()?.takeIf { it.isNotEmpty() } ?: return currentSource
    if (sourceKey == currentSource.getKey()) return currentSource
    return findBookSource(sourceKey) ?: findRssSource(sourceKey)
}

internal fun createSourceLoginRoute(
    targetSource: BaseSource,
    currentSource: BaseSource,
    currentBookType: Int,
): SourceLoginRoute? {
    return when (targetSource) {
        is BookSource -> SourceLoginRoute(
            type = "bookSource",
            key = targetSource.bookSourceUrl,
            bookType = currentBookType.takeIf {
                it != 0 && currentSource is BookSource &&
                    currentSource.bookSourceUrl == targetSource.bookSourceUrl
            },
        )

        is RssSource -> SourceLoginRoute(
            type = "rssSource",
            key = targetSource.sourceUrl,
        )

        else -> null
    }
}
