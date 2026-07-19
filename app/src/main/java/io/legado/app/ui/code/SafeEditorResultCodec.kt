package io.legado.app.ui.code

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

internal data class SafeEditorContent(
    val text: String,
    val cursorPosition: Int,
    val dirty: Boolean
) {

    fun resolveAgainst(initialText: String): SafeEditorContent {
        if (dirty) {
            return copy(cursorPosition = cursorPosition.coerceIn(0, text.length))
        }
        return copy(
            text = initialText,
            cursorPosition = mapNormalizedCursorToOriginal(initialText, cursorPosition)
        )
    }

    private fun mapNormalizedCursorToOriginal(originalText: String, cursor: Int): Int {
        val target = cursor.coerceAtLeast(0)
        var originalIndex = 0
        var normalizedIndex = 0
        while (originalIndex < originalText.length && normalizedIndex < target) {
            if (originalText[originalIndex] == '\r') {
                originalIndex += if (
                    originalIndex + 1 < originalText.length &&
                    originalText[originalIndex + 1] == '\n'
                ) 2 else 1
            } else {
                originalIndex++
            }
            normalizedIndex++
        }
        return originalIndex.coerceAtMost(originalText.length)
    }
}

internal object SafeEditorResultCodec {

    private val gson = Gson()

    fun decode(value: String?): SafeEditorContent? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val payloadJson = gson.fromJson(value, String::class.java)
                ?: return@runCatching null
            val payload = gson.fromJson(payloadJson, Payload::class.java)
                ?: return@runCatching null
            SafeEditorContent(
                text = payload.text ?: return@runCatching null,
                cursorPosition = payload.cursorPosition?.coerceAtLeast(0) ?: 0,
                dirty = payload.dirty ?: return@runCatching null
            )
        }.getOrNull()
    }

    private data class Payload(
        @SerializedName("text") val text: String? = null,
        @SerializedName("cursorPosition") val cursorPosition: Int? = null,
        @SerializedName("dirty") val dirty: Boolean? = null
    )
}
