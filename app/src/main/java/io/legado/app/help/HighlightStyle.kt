package io.legado.app.help

/**
 * 可组合的正文高亮样式。颜色值为 ARGB，0 或 null 表示对应通道关闭。
 */
data class HighlightStyle(
    val fill: Int = 0,
    val fillShape: FillShape? = null,
    val textColor: Int = 0,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Underline? = null,
    val strike: Deco? = null,
    val box: Deco? = null,
    val emphasis: Deco? = null,
    val shadow: Shadow? = null
) {
    data class Underline(val kind: Kind = Kind.SOLID, val color: Int = 0)

    data class Deco(val color: Int = 0)

    data class Shadow(
        val radius: Float = 3f,
        val dx: Float = 2f,
        val dy: Float = 2f,
        val color: Int = 0x80000000.toInt()
    ) {
        fun normalized() = copy(
            radius = radius.takeIf { it.isFinite() }?.coerceIn(0f, 10f) ?: 0f,
            dx = dx.takeIf { it.isFinite() }?.coerceIn(-10f, 10f) ?: 0f,
            dy = dy.takeIf { it.isFinite() }?.coerceIn(-10f, 10f) ?: 0f
        )
    }

    enum class Kind { SOLID, WAVY, DASHED, DOTTED, DOUBLE }

    enum class FillShape { RECTANGLE, ROUNDED, MARKER, HALF, BASELINE, PILL }

    val resolvedFillShape: FillShape
        get() = fillShape ?: FillShape.RECTANGLE

    val isEmpty: Boolean
        get() = fill == 0 && textColor == 0 && !bold && !italic &&
            underline == null && strike == null && box == null && emphasis == null &&
            shadow == null

    val needsPerColumnDraw: Boolean
        get() = textColor != 0 || bold || italic || underline != null || strike != null ||
            box != null || emphasis != null || shadow != null

    companion object {
        fun merge(base: HighlightStyle?, other: HighlightStyle): HighlightStyle {
            val current = base ?: HighlightStyle()
            return current.copy(
                fill = if (other.fill != 0) other.fill else current.fill,
                fillShape = if (other.fill != 0) other.fillShape else current.fillShape,
                textColor = if (other.textColor != 0) other.textColor else current.textColor,
                bold = other.bold || current.bold,
                italic = other.italic || current.italic,
                underline = other.underline ?: current.underline,
                strike = other.strike ?: current.strike,
                box = other.box ?: current.box,
                emphasis = other.emphasis ?: current.emphasis,
                shadow = other.shadow ?: current.shadow
            )
        }
    }
}
