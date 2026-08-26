package io.legado.app.help.config

object BookshelfReadProgressMode {
    const val HIDDEN = 0
    const val STANDARD = 1
    const val ENHANCED = 2
    const val STANDARD_THICKNESS_DP = 2
    const val ENHANCED_THICKNESS_DP = 4

    fun normalize(value: Int): Int = value.coerceIn(HIDDEN, ENHANCED)

    fun resolve(storedMode: Any?, legacyEnabled: Any?): Int {
        storedMode.asIntOrNull()?.let { return normalize(it) }
        val enabled = when (legacyEnabled) {
            is Boolean -> legacyEnabled
            is String -> legacyEnabled.toBooleanStrictOrNull()
            else -> null
        }
        return if (enabled == false) HIDDEN else STANDARD
    }

    fun thicknessDp(mode: Int): Int =
        if (normalize(mode) == ENHANCED) ENHANCED_THICKNESS_DP else STANDARD_THICKNESS_DP
}

private fun Any?.asIntOrNull(): Int? = when (this) {
    is Number -> toInt()
    is String -> trim().toIntOrNull()
    else -> null
}
