package io.legado.app.help.book

import io.legado.app.data.entities.ReplaceRule

data class BookContent(
    val sameTitleRemoved: Boolean,
    val textList: List<String>,
    //起效的替换规则
    val effectiveReplaceRules: List<ReplaceRule>?,
    val titleLength: Int = 0,
    val displayTitle: String? = null,
) {

    override fun toString(): String {
        return textList.joinToString("\n")
    }

}
