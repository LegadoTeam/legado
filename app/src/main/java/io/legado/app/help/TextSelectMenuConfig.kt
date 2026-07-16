package io.legado.app.help

import com.google.gson.Gson

data class TextSelectMenuConfig(
    val bar: List<String> = emptyList(),
    val more: List<String> = emptyList()
) {

    fun toJson(): String = gson.toJson(this)

    fun normalized(knownKeys: List<String> = ALL_KEYS): TextSelectMenuConfig {
        val known = knownKeys.toHashSet()
        val seen = LinkedHashSet<String>()
        val normalizedBar = bar.filterTo(ArrayList()) { it in known && seen.add(it) }
        val normalizedMore = more.filterTo(ArrayList()) { it in known && seen.add(it) }
        knownKeys.filterTo(normalizedMore) { seen.add(it) }
        return TextSelectMenuConfig(normalizedBar, normalizedMore)
    }

    fun <T> partitionItems(
        builtInItems: Map<String, T>,
        processTextItems: List<T>,
        allItems: List<T>
    ): TextSelectMenuPartition<T> {
        val normalized = normalized()
        val placed = LinkedHashSet<T>()
        val barItems = ArrayList<T>()
        val moreItems = ArrayList<T>()

        fun addItems(key: String, target: MutableList<T>) {
            val items = if (key == KEY_PROCESS_TEXT) {
                processTextItems
            } else {
                listOfNotNull(builtInItems[key])
            }
            items.filterTo(target) { placed.add(it) }
        }

        normalized.bar.forEach { addItems(it, barItems) }
        normalized.more.forEach { addItems(it, moreItems) }
        allItems.filterTo(moreItems) { placed.add(it) }
        return TextSelectMenuPartition(barItems, moreItems)
    }

    companion object {
        const val KEY_REPLACE = "replace"
        const val KEY_COPY = "copy"
        const val KEY_BOOKMARK = "bookmark"
        const val KEY_ALOUD = "aloud"
        const val KEY_DICT = "dict"
        const val KEY_SEARCH = "search"
        const val KEY_BROWSER = "browser"
        const val KEY_SHARE = "share"
        const val KEY_PROCESS_TEXT = "processText"

        val ALL_KEYS = listOf(
            KEY_REPLACE,
            KEY_COPY,
            KEY_BOOKMARK,
            KEY_ALOUD,
            KEY_DICT,
            KEY_SEARCH,
            KEY_BROWSER,
            KEY_SHARE,
            KEY_PROCESS_TEXT
        )

        val DEFAULT_BAR = listOf(KEY_REPLACE, KEY_COPY, KEY_BOOKMARK, KEY_ALOUD, KEY_DICT)
        val DEFAULT_MORE = listOf(KEY_SEARCH, KEY_BROWSER, KEY_SHARE, KEY_PROCESS_TEXT)

        private val gson = Gson()

        fun default() = TextSelectMenuConfig(DEFAULT_BAR, DEFAULT_MORE)

        fun fromJson(json: String?): TextSelectMenuConfig {
            if (json.isNullOrBlank()) return default()
            val parsed = runCatching {
                gson.fromJson(json, TextSelectMenuConfig::class.java)
            }.getOrNull() ?: return default()
            @Suppress("USELESS_ELVIS")
            return TextSelectMenuConfig(parsed.bar ?: emptyList(), parsed.more ?: emptyList())
        }

        fun migrateFrom(expandTextMenu: Boolean): TextSelectMenuConfig {
            return if (expandTextMenu) {
                TextSelectMenuConfig(ALL_KEYS, emptyList())
            } else {
                default()
            }
        }
    }
}

data class TextSelectMenuPartition<T>(
    val bar: List<T>,
    val more: List<T>
)
