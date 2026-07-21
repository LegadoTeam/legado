package io.legado.app.help.audio

import io.legado.app.model.AudioCacheKey
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.normalizeFileName
import java.util.Locale

object AudioCachePolicy {

    private val cacheFileRegex = Regex(
        "^(\\d{5,})_([0-9a-f]{16})_.+_([0-9a-f]{16})_([0-9a-f]{8})\\.([a-z0-9]{2,6})$"
    )

    private val audioExtensions = setOf(
        "mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "wav", "flac",
        "webm", "amr", "3gp"
    )

    fun normalizeRange(start: Int, endInclusive: Int, chapterCount: Int): IntRange? {
        if (start < 0 || endInclusive < start || start >= chapterCount) return null
        return start..minOf(endInclusive, chapterCount - 1)
    }

    internal fun requireCacheablePlayUrl(playUrl: String) {
        if (playUrl.isBlank()) throw IllegalStateException("播放链接为空")
        if (playUrl.isJsonArray()) {
            throw UnsupportedOperationException("暂不支持缓存多段音频")
        }
        if (isHlsUrl(playUrl)) {
            throw UnsupportedOperationException("暂不支持缓存 HLS 音频")
        }
    }

    internal fun detectExtension(
        contentType: String?,
        finalUrl: String,
        playUrl: String,
    ): String {
        requireCacheablePlayUrl(playUrl)
        val normalizedContentType = contentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
        if (normalizedContentType?.contains("mpegurl") == true || isHlsUrl(finalUrl)) {
            throw UnsupportedOperationException("暂不支持缓存 HLS 音频")
        }
        return when {
            normalizedContentType == null -> extensionFromUrl(finalUrl)
                ?: extensionFromUrl(playUrl)
                ?: "audio"

            "mpeg" in normalizedContentType || "mp3" in normalizedContentType -> "mp3"
            "m4a" in normalizedContentType || "mp4" in normalizedContentType -> "m4a"
            "aac" in normalizedContentType -> "aac"
            "ogg" in normalizedContentType -> "ogg"
            "opus" in normalizedContentType -> "opus"
            "wav" in normalizedContentType -> "wav"
            "flac" in normalizedContentType -> "flac"
            "webm" in normalizedContentType -> "webm"
            "amr" in normalizedContentType -> "amr"
            "3gpp" in normalizedContentType -> "3gp"
            else -> extensionFromUrl(finalUrl) ?: extensionFromUrl(playUrl) ?: "audio"
        }
    }

    internal fun buildFileName(
        chapterIndex: Int,
        key: AudioCacheKey,
        chapterTitle: String,
        playUrlHash: String,
        revision: String,
        extension: String,
    ): String {
        require(chapterIndex >= 0)
        require(playUrlHash.length == 16 && playUrlHash.all { it.isDigit() || it in 'a'..'f' })
        require(revision.length == 8 && revision.all { it.isDigit() || it in 'a'..'f' })
        require(extension == "audio" || extension in audioExtensions)
        val safeTitle = chapterTitle
            .normalizeFileName()
            .filterNot(Char::isISOControl)
            .trim()
            .trim('_')
            .ifBlank { "chapter" }
            .take(40)
            .trimEnd('.', ' ')
            .ifBlank { "chapter" }
        return String.format(
            Locale.ROOT,
            "%05d_%s_%s_%s_%s.%s",
            chapterIndex,
            key.value,
            safeTitle,
            playUrlHash,
            revision,
            extension
        )
    }

    internal fun parseFileName(fileName: String): CachedAudioFile? {
        val match = cacheFileRegex.matchEntire(fileName) ?: return null
        val key = AudioCacheKey.parse(match.groupValues[2]) ?: return null
        val extension = match.groupValues[5]
        if (extension != "audio" && extension !in audioExtensions) return null
        return CachedAudioFile(
            chapterIndex = match.groupValues[1].toIntOrNull() ?: return null,
            key = key,
            extension = extension
        )
    }

    internal fun isCompleteFile(actualSize: Long, expectedSize: Long?): Boolean {
        return actualSize > 0L && (expectedSize == null || actualSize == expectedSize)
    }

    private fun isHlsUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#')
        return path.endsWith(".m3u8", true) || path.endsWith(".m3u", true)
    }

    private fun extensionFromUrl(url: String): String? {
        val extension = url
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
        return extension.takeIf { it in audioExtensions }
    }
}

internal data class CachedAudioFile(
    val chapterIndex: Int,
    val key: AudioCacheKey,
    val extension: String,
)

internal object AudioCacheMetadata {

    private const val VERSION = "1"

    fun encode(playUrl: String): String {
        return "$VERSION\n${MD5Utils.md5Encode16(playUrl)}\n$playUrl"
    }

    fun decode(metadata: String): String? {
        val parts = metadata.split('\n', limit = 3)
        if (parts.size != 3 || parts[0] != VERSION) return null
        val playUrl = parts[2]
        return playUrl.takeIf {
            it.isNotBlank() && MD5Utils.md5Encode16(it) == parts[1]
        }
    }
}
