package io.legado.app.ui.widget.code

/**
 * Keeps pathological combining-character sequences away from Android text layout engines.
 */
object EditSafety {

    const val PREVIEW_LINES = 6
    const val MAX_INLINE_TEXT_LENGTH = 12_000

    private const val DENSITY_WINDOW = 64
    private const val MAX_COMBINING_RUN = 8
    private const val MIN_DENSITY_COUNT = 16

    data class Presentation(
        val text: String,
        val isInlineEditable: Boolean
    )

    fun isTooLongForInline(text: CharSequence): Boolean =
        text.length > MAX_INLINE_TEXT_LENGTH

    fun presentation(originalText: String, placeholder: String): Presentation {
        val isUnsafe = isCombiningHeavy(originalText)
        return Presentation(
            text = if (isUnsafe) placeholder else originalText,
            isInlineEditable = !isUnsafe
        )
    }

    fun isCombiningHeavy(text: CharSequence): Boolean {
        if (text.isEmpty()) return false

        var index = 0
        var combiningRun = 0
        var densityWindowSize = 0
        var densityWindowIndex = 0
        var densityCombiningCount = 0
        val densityWindow = BooleanArray(DENSITY_WINDOW)

        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            val isCombining = isCombiningMark(codePoint) && !isVariationSelector(codePoint)

            if (isCombining) {
                combiningRun++
                if (combiningRun >= MAX_COMBINING_RUN) return true
            } else {
                combiningRun = 0
            }

            if (densityWindowSize == DENSITY_WINDOW) {
                if (densityWindow[densityWindowIndex]) densityCombiningCount--
            } else {
                densityWindowSize++
            }
            densityWindow[densityWindowIndex] = isCombining
            if (isCombining) densityCombiningCount++
            densityWindowIndex = (densityWindowIndex + 1) % DENSITY_WINDOW

            if (
                densityCombiningCount >= MIN_DENSITY_COUNT &&
                densityCombiningCount * 10 >= densityWindowSize * 3
            ) return true

            index += Character.charCount(codePoint)
        }

        return false
    }

    private fun isCombiningMark(codePoint: Int): Boolean {
        return when (Character.getType(codePoint)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt() -> true

            else -> false
        }
    }

    private fun isVariationSelector(codePoint: Int): Boolean {
        return codePoint in 0xFE00..0xFE0F || codePoint in 0xE0100..0xE01EF
    }
}
