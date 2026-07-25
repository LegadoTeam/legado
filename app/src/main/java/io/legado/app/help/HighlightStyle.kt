package io.legado.app.help

/**
 * 可组合的正文高亮样式。颜色值为 ARGB，0 或 null 表示对应通道关闭。
 */
data class HighlightStyle(
    val fill: Int = 0,
    val textColor: Int = 0,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Underline? = null,
    val strike: Deco? = null,
    val box: Deco? = null,
    val emphasis: Deco? = null
) {
    data class Underline(val kind: Kind = Kind.SOLID, val color: Int = 0)

    data class Deco(val color: Int = 0)

    enum class Kind { SOLID, WAVY, DASHED, DOTTED, DOUBLE }

    val isEmpty: Boolean
        get() = fill == 0 && textColor == 0 && !bold && !italic &&
            underline == null && strike == null && box == null && emphasis == null

    val needsPerColumnDraw: Boolean
        get() = textColor != 0 || bold || italic || underline != null || strike != null ||
            box != null || emphasis != null

    companion object {
        fun merge(base: HighlightStyle?, other: HighlightStyle): HighlightStyle {
            val current = base ?: HighlightStyle()
            return current.copy(
                fill = if (other.fill != 0) other.fill else current.fill,
                textColor = if (other.textColor != 0) other.textColor else current.textColor,
                bold = other.bold || current.bold,
                italic = other.italic || current.italic,
                underline = other.underline ?: current.underline,
                strike = other.strike ?: current.strike,
                box = other.box ?: current.box,
                emphasis = other.emphasis ?: current.emphasis
            )
        }
    }
}
