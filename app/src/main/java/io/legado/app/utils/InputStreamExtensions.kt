package io.legado.app.utils

import java.io.InputStream
import java.util.*

private const val JSON_SNIFF_LIMIT = 64 * 1024

fun InputStream?.looksLikeJson(): Boolean {
    this ?: return false
    return use { input ->
        val prefix = ByteArray(3)
        var prefixSize = 0
        while (prefixSize < prefix.size) {
            val value = input.read()
            if (value < 0) break
            prefix[prefixSize++] = value.toByte()
        }
        var scanned = prefixSize
        var index = 0
        while (index < prefixSize) {
            val value = prefix[index].toInt() and 0xff
            if (!value.isJsonWhitespace()) {
                return@use value == '{'.code || value == '['.code
            }
            index++
        }

        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (scanned < JSON_SNIFF_LIMIT) {
            val count = input.read(
                buffer,
                0,
                minOf(buffer.size, JSON_SNIFF_LIMIT - scanned),
            )
            if (count < 0) return@use false
            if (count == 0) {
                val value = input.read()
                if (value < 0) return@use false
                scanned++
                if (!value.isJsonWhitespace()) {
                    return@use value == '{'.code || value == '['.code
                }
                continue
            }
            scanned += count
            for (index in 0 until count) {
                val value = buffer[index].toInt() and 0xff
                if (value.isJsonWhitespace()) continue
                return@use value == '{'.code || value == '['.code
            }
        }
        false
    }
}

private fun Int.isJsonWhitespace(): Boolean =
    this == ' '.code || this == '\t'.code || this == '\n'.code || this == '\r'.code

fun InputStream?.contains(str: String): Boolean {
    this ?: return false
    this.use {
        val scanner = Scanner(it)
        return scanner.findWithinHorizon(str, 0) != null
    }
}
