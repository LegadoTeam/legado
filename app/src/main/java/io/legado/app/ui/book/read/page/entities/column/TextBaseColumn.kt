package io.legado.app.ui.book.read.page.entities.column

import io.legado.app.help.HighlightStyle

/**
 * 文字基列
 */
interface TextBaseColumn : BaseColumn {
    override var start: Float
    override var end: Float
    val charData: String
    override val positionLength: Int get() = charData.length
    var selected: Boolean
    var isSearchResult: Boolean
    var highlightStyle: HighlightStyle?
}
