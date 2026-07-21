package io.legado.app.model

import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.MD5Utils

data class AudioCacheKey(val value: String) {

    init {
        require(value.length == 16 && value.all { it.isDigit() || it in 'a'..'f' })
    }

    override fun toString(): String = value

    companion object {

        fun from(chapter: BookChapter): AudioCacheKey {
            return from(chapter.url, chapter.title)
        }

        internal fun from(chapterUrl: String, chapterTitle: String): AudioCacheKey {
            val identity = chapterUrl.ifBlank { chapterTitle }
            return AudioCacheKey(MD5Utils.md5Encode16(identity))
        }

        internal fun parse(value: String): AudioCacheKey? {
            return runCatching { AudioCacheKey(value.lowercase()) }.getOrNull()
        }
    }
}
