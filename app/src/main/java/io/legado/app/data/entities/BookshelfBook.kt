package io.legado.app.data.entities

data class BookshelfBook(
    val bookUrl: String,
    val origin: String,
    val name: String,
    val author: String,
    val coverUrl: String?,
    val customCoverUrl: String?,
    val type: Int,
    val group: Long,
    val hasUserGroup: Boolean,
    val latestChapterTime: Long,
    val durChapterTime: Long,
    val order: Int,
    val persistedCoverUrl: String? = null,
) {
    val displayCover: String?
        get() = persistedCoverUrl?.takeIf { it.isNotEmpty() }
            ?: customCoverUrl?.takeIf { it.isNotEmpty() }
            ?: coverUrl

    val coverSourceOrigin: String?
        get() = if (persistedCoverUrl.isNullOrEmpty()) {
            coverSourceOrigin(origin, customCoverUrl)
        } else {
            null
        }
}
