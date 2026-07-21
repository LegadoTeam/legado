package io.legado.app.model

data class AudioCacheStateChanged(
    val bookUrl: String,
    val key: AudioCacheKey,
    val cached: Boolean,
    val treeUri: String?,
)
