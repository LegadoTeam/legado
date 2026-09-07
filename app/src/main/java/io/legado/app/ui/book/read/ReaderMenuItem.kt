package io.legado.app.ui.book.read

import android.content.Context
import android.view.Menu
import android.view.MenuItem
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.ReaderMenuConfig
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString

/** Stable logical actions exposed by the reader overflow menu. */
enum class ReaderMenuItem(
    val key: String,
    private val menuIds: IntArray,
    val titleRes: Int
) {
    Bookmark("bookmark", intArrayOf(R.id.menu_add_bookmark), R.string.bookmark_add),
    HighlightRule("highlightRule", intArrayOf(R.id.menu_highlight_rule), R.string.highlight_rule),
    EditContent("editContent", intArrayOf(R.id.menu_edit_content), R.string.edit_content),
    PageAnim("pageAnim", intArrayOf(R.id.menu_page_anim), R.string.book_page_anim),
    GetProgress("getProgress", intArrayOf(R.id.menu_get_progress), R.string.get_book_progress),
    CoverProgress("coverProgress", intArrayOf(R.id.menu_cover_progress), R.string.cover_book_progress),
    ReverseContent("reverseContent", intArrayOf(R.id.menu_reverse_content), R.string.reverse_content),
    SimulatedReading(
        "simulatedReading",
        intArrayOf(R.id.menu_simulated_reading),
        R.string.simulated_reading
    ),
    Replace(
        "replace",
        intArrayOf(R.id.menu_enable_replace, R.id.menu_manual_replace_rule),
        R.string.replace_rule_title
    ),
    SameTitleRemoved(
        "sameTitleRemoved",
        intArrayOf(R.id.menu_same_title_removed),
        R.string.same_title_removed
    ),
    ReSegment("reSegment", intArrayOf(R.id.menu_re_segment), R.string.re_segment),
    DelRubyTag("delRubyTag", intArrayOf(R.id.menu_del_ruby_tag), R.string.del_ruby_tag),
    DelHTag("delHTag", intArrayOf(R.id.menu_del_h_tag), R.string.del_h_tag),
    ImageStyle("imageStyle", intArrayOf(R.id.menu_image_style), R.string.image_style),
    UpdateToc("updateToc", intArrayOf(R.id.menu_update_toc), R.string.update_toc),
    EffectiveReplaces(
        "effectiveReplaces",
        intArrayOf(R.id.menu_effective_replaces),
        R.string.effective_replaces
    ),
    Log("log", intArrayOf(R.id.menu_log), R.string.log),
    Help("help", intArrayOf(R.id.menu_help), R.string.help);

    fun findVisible(menu: Menu): MenuItem? {
        return menuIds.asSequence()
            .mapNotNull { id -> menu.findItem(id) }
            .firstOrNull(MenuItem::isVisible)
    }

    companion object {
        val byKey: Map<String, ReaderMenuItem> = entries.associateBy { it.key }
    }
}

fun loadReaderMenuConfig(context: Context): ReaderMenuConfig {
    val json = context.getPrefString(PreferKey.readerMenuConfig)
    if (json.isNullOrBlank()) {
        val config = ReaderMenuConfig.default()
        saveReaderMenuConfig(context, config)
        return config
    }
    return ReaderMenuConfig.fromJson(json).normalized()
}

fun saveReaderMenuConfig(context: Context, config: ReaderMenuConfig) {
    context.putPrefString(
        PreferKey.readerMenuConfig,
        config.normalized().toJson()
    )
}
