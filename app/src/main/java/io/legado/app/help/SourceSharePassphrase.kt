package io.legado.app.help

import java.net.URI
import kotlin.random.Random

object SourceSharePassphrase {

    const val MAX_EXPIRY_DAYS = 36500
    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    private const val EXPIRY_PRECISION = 1_000_000L
    private const val HTTPS_MARKER = "#L:"
    private const val HTTP_MARKER = "#L0:"
    private const val PREFIX = "复制口令到阅读导入"
    private const val CUSTOM_WORD = "Legado"

    private val mappings = linkedMapOf(
        "https://" to listOf(HTTPS_MARKER),
        "http://" to listOf(HTTP_MARKER),
        "." to listOf("电", "店", "垫", "殿", "。"),
        "%" to listOf("白", "百", "拜", "摆", "💯"),
        "/" to listOf("杠", "刚", "钢", "岗", "🎹"),
        "zip" to listOf("压", "亚", "呀", "牙", "🦆"),
        "json" to listOf("串", "穿", "船", "传", "🚢"),
        "4" to listOf("四", "是", "时", "丝", "🕓"),
        "5" to listOf("五", "武", "误", "勿", "🕔"),
        "6" to listOf("六", "刘", "留", "陆", "🕕"),
        "0" to listOf("零", "另", "玲", "灵", "⏰"),
        "com" to listOf("🛜1", "🌐1", "🌏1"),
        "cn" to listOf("🛜2", "🌐2", "🌏2"),
        "net" to listOf("🛜3", "🌐3", "🌏3"),
        "org" to listOf("🛜7", "🌐7", "🌏7"),
        "xyz" to listOf("🛜8", "🌐8", "🌏8"),
        "me" to listOf("🛜9", "🌐9", "🌏9"),
    )
    private val encodeKeys = mappings.keys.sortedByDescending(String::length)
    private val reverseMappings = mappings.flatMap { (original, replacements) ->
        replacements.map { replacement -> replacement to original }
    }.sortedByDescending { it.first.length }
    private val unsafeUrlTokens = mappings.values.flatten() + listOf("！", "©", "¥", "^")

    enum class Type(val code: String) {
        BOOK_SOURCE("sy"),
        RSS_SOURCE("dy"),
        DICT_RULE("zd"),
        REPLACE_RULE("jh"),
        TOC_RULE("ml"),
        TTS_RULE("ld");

        companion object {
            fun fromCode(code: String): Type? = entries.firstOrNull { it.code == code }
        }
    }

    data class Value(
        val url: String,
        val type: Type,
        val expiresAt: Long,
        val customWord: String,
    )

    sealed interface DecodeResult {
        data class Success(val value: Value) : DecodeResult
        data object NotFound : DecodeResult
        data object Invalid : DecodeResult
        data object Expired : DecodeResult
    }

    fun canEncode(url: String): Boolean {
        return isSupportedUrl(url) && unsafeUrlTokens.none(url::contains)
    }

    fun encode(
        url: String,
        type: Type,
        expiryDays: Int,
        time: Long = System.currentTimeMillis(),
    ): String {
        require(canEncode(url)) { "URL cannot be safely shared as a passphrase" }
        val random = Random(time)
        val encodedUrl = buildString {
            var index = 0
            while (index < url.length) {
                val key = encodeKeys.firstOrNull { url.startsWith(it, index) }
                if (key == null) {
                    append(url[index])
                    index++
                } else {
                    val replacements = mappings.getValue(key)
                    append(replacements[random.nextInt(replacements.size)])
                    index += key.length
                }
            }
        }
        val safeExpiryDays = expiryDays.coerceIn(0, MAX_EXPIRY_DAYS)
        val expiresAt = if (safeExpiryDays == 0) {
            0L
        } else {
            time + safeExpiryDays * MILLIS_PER_DAY
        }
        val expiryToken = expiresAt.toString().take(7)
        return "$PREFIX${encodedUrl}！${type.code}©${expiryToken}¥${CUSTOM_WORD}^"
    }

    fun decode(
        text: String,
        time: Long = System.currentTimeMillis(),
    ): DecodeResult {
        val prefixIndex = text.indexOf(PREFIX)
        if (prefixIndex < 0) return DecodeResult.NotFound
        val urlStart = prefixIndex + PREFIX.length
        if (!text.startsWith(HTTPS_MARKER, urlStart) &&
            !text.startsWith(HTTP_MARKER, urlStart)
        ) {
            return DecodeResult.Invalid
        }
        val typeSeparator = text.indexOf('！', urlStart)
        if (typeSeparator <= urlStart) return DecodeResult.Invalid
        val metadataSeparator = text.indexOf('©', typeSeparator + 1)
        if (metadataSeparator <= typeSeparator + 1) return DecodeResult.Invalid
        val expirySeparator = text.indexOf('¥', metadataSeparator + 1)
        if (expirySeparator <= metadataSeparator + 1) return DecodeResult.Invalid
        val suffixEnd = text.indexOf('^', expirySeparator + 1)
        if (suffixEnd <= expirySeparator + 1) return DecodeResult.Invalid

        val type = Type.fromCode(text.substring(typeSeparator + 1, metadataSeparator))
            ?: return DecodeResult.Invalid
        val expiryToken = text.substring(metadataSeparator + 1, expirySeparator)
        if (expiryToken != "0" &&
            (expiryToken.length != 7 || expiryToken.any { it !in '0'..'9' })
        ) {
            return DecodeResult.Invalid
        }
        val expiresAt = expiryToken.toLong() * EXPIRY_PRECISION
        if (expiresAt > 0 && expiresAt < time) return DecodeResult.Expired

        val url = decodeUrl(text.substring(urlStart, typeSeparator))
        if (!isSupportedUrl(url)) return DecodeResult.Invalid
        return DecodeResult.Success(
            Value(
                url = url,
                type = type,
                expiresAt = expiresAt,
                customWord = text.substring(expirySeparator + 1, suffixEnd),
            )
        )
    }

    private fun decodeUrl(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val mapping = reverseMappings.firstOrNull { value.startsWith(it.first, index) }
            if (mapping == null) {
                append(value[index])
                index++
            } else {
                append(mapping.second)
                index += mapping.first.length
            }
        }
    }

    private fun isSupportedUrl(url: String): Boolean {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return false
        return runCatching { URI(url).toURL().host.isNotBlank() }.getOrDefault(false)
    }
}
