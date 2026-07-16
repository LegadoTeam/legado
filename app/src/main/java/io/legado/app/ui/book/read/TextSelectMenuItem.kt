package io.legado.app.ui.book.read

import android.content.Context
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.TextSelectMenuConfig
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString

enum class TextSelectMenuItem(val key: String, val menuId: Int?, val titleRes: Int) {
    Replace(TextSelectMenuConfig.KEY_REPLACE, R.id.menu_replace, R.string.replace),
    Copy(TextSelectMenuConfig.KEY_COPY, R.id.menu_copy, android.R.string.copy),
    Bookmark(TextSelectMenuConfig.KEY_BOOKMARK, R.id.menu_bookmark, R.string.bookmark),
    Aloud(TextSelectMenuConfig.KEY_ALOUD, R.id.menu_aloud, R.string.read_aloud),
    Dict(TextSelectMenuConfig.KEY_DICT, R.id.menu_dict, R.string.dict),
    Search(TextSelectMenuConfig.KEY_SEARCH, R.id.menu_search_content, R.string.search_content),
    Browser(TextSelectMenuConfig.KEY_BROWSER, R.id.menu_browser, R.string.browser),
    Share(TextSelectMenuConfig.KEY_SHARE, R.id.menu_share_str, R.string.share),
    ProcessText(TextSelectMenuConfig.KEY_PROCESS_TEXT, null, R.string.process_text_actions);

    companion object {
        val byKey: Map<String, TextSelectMenuItem> = entries.associateBy { it.key }
    }
}

fun loadTextSelectMenuConfig(context: Context): TextSelectMenuConfig {
    val json = context.getPrefString(PreferKey.textSelectMenuConfig)
    if (json.isNullOrBlank()) {
        val migrated = TextSelectMenuConfig.migrateFrom(
            context.getPrefBoolean(PreferKey.expandTextMenu)
        )
        context.putPrefString(PreferKey.textSelectMenuConfig, migrated.toJson())
        return migrated.normalized()
    }
    return TextSelectMenuConfig.fromJson(json).normalized()
}

fun saveTextSelectMenuConfig(context: Context, config: TextSelectMenuConfig) {
    context.putPrefString(PreferKey.textSelectMenuConfig, config.normalized().toJson())
}
