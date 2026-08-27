package io.legado.app.help

import com.google.gson.Gson

/**
 * The user-facing partition and order of the reader overflow actions.
 *
 * Actions that are unavailable for the current book are simply omitted when
 * the menu is rendered; they remain in this config so a later book can use the
 * same preference without migration.
 */
data class ReaderMenuConfig(
    val primary: List<String> = emptyList(),
    val more: List<String> = emptyList()
) {

    fun toJson(): String = gson.toJson(this)

    fun normalized(knownKeys: List<String> = ALL_KEYS): ReaderMenuConfig {
        val known = knownKeys.toHashSet()
        val seen = LinkedHashSet<String>()
        val normalizedPrimary = primary.filterTo(ArrayList()) {
            it in known && seen.add(it)
        }
        val normalizedMore = more.filterTo(ArrayList()) {
            it in known && seen.add(it)
        }
        // New actions default to the first-level menu, matching the initial
        // configuration where every supported action is selected.
        knownKeys.filterTo(normalizedPrimary) { seen.add(it) }
        return ReaderMenuConfig(normalizedPrimary, normalizedMore)
    }

    companion object {
        private val gson = Gson()

        val ALL_KEYS = listOf(
            "bookmark",
            "highlightRule",
            "editContent",
            "pageAnim",
            "getProgress",
            "coverProgress",
            "reverseContent",
            "simulatedReading",
            "replace",
            "sameTitleRemoved",
            "reSegment",
            "delRubyTag",
            "delHTag",
            "imageStyle",
            "updateToc",
            "effectiveReplaces",
            "log",
            "help"
        )

        fun default(): ReaderMenuConfig = ReaderMenuConfig(primary = ALL_KEYS)

        fun fromJson(json: String?): ReaderMenuConfig {
            if (json.isNullOrBlank()) return default()
            val parsed = runCatching {
                gson.fromJson(json, ReaderMenuConfig::class.java)
            }.getOrNull() ?: return default()
            @Suppress("USELESS_ELVIS")
            return ReaderMenuConfig(
                primary = parsed.primary ?: emptyList(),
                more = parsed.more ?: emptyList()
            )
        }
    }
}
