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
    val shadow: Shadow? = null,
    val fontPath: String = "",
    val pillPaddingScale: Float? = null,
    val fontSize: Float? = null,
    val letterSpacing: Float? = null,
    // Requested ink-to-edge distance in dp; null keeps the legacy shape and layout.
    val horizontalPadding: Float? = null
) {
    data class Underline(
        val kind: Kind = Kind.SOLID,
        val color: Int = 0,
        val width: Float = DEFAULT_WIDTH,
        val distance: Float = DEFAULT_DISTANCE
    ) {
        fun normalized(): Underline {
            @Suppress("USELESS_ELVIS")
            val normalizedKind = kind ?: Kind.SOLID
            val normalizedWidth = width.takeIf { it.isFinite() }
                ?.coerceIn(MIN_WIDTH, MAX_WIDTH)
                ?: DEFAULT_WIDTH
            val normalizedDistance = distance.takeIf { it.isFinite() }
                ?.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
                ?: DEFAULT_DISTANCE
            return if (
                normalizedKind == kind &&
                normalizedWidth == width &&
                normalizedDistance == distance
            ) {
                this
            } else {
                copy(
                    kind = normalizedKind,
                    width = normalizedWidth,
                    distance = normalizedDistance
                )
            }
        }

        companion object {
            const val DEFAULT_WIDTH = 1f
            const val MIN_WIDTH = 0f
            const val MAX_WIDTH = 10f
            const val DEFAULT_DISTANCE = 0f
            const val MIN_DISTANCE = 0f
            const val MAX_DISTANCE = 30f
        }
    }

    data class Deco(val color: Int = 0)

    data class Shadow(
        val radius: Float = 3f,
        val dx: Float = 2f,
        val dy: Float = 2f,
        val color: Int = 0x80000000.toInt()
    ) {
        fun normalized(): Shadow {
            val normalizedRadius = radius.takeIf { it.isFinite() }?.coerceIn(0f, 10f) ?: 0f
            val normalizedDx = dx.takeIf { it.isFinite() }?.coerceIn(-10f, 10f) ?: 0f
            val normalizedDy = dy.takeIf { it.isFinite() }?.coerceIn(-10f, 10f) ?: 0f
            return if (
                normalizedRadius == radius && normalizedDx == dx && normalizedDy == dy
            ) {
                this
            } else {
                copy(radius = normalizedRadius, dx = normalizedDx, dy = normalizedDy)
            }
        }
    }

    enum class Kind { SOLID, WAVY, DASHED, DOTTED, DOUBLE }

    enum class FillShape { RECTANGLE, ROUNDED, MARKER, HALF, BASELINE, PILL }

    val resolvedFillShape: FillShape
        get() = fillShape ?: FillShape.RECTANGLE

    val resolvedFontPath: String
        get() = fontPath.orEmpty()

    val resolvedPillPaddingScale: Float
        get() = pillPaddingScale?.takeIf { it.isFinite() }?.coerceIn(0.25f, 2f) ?: 1f

    val resolvedFontSize: Float?
        get() = fontSize?.takeIf { it.isFinite() }?.coerceIn(5f, 100f)

    val resolvedLetterSpacing: Float?
        get() = letterSpacing?.takeIf { it.isFinite() }?.coerceIn(-0.5f, 1f)

    val resolvedHorizontalPadding: Float?
        get() = horizontalPadding?.takeIf { it.isFinite() }?.coerceIn(0f, 64f)

    val changesTextMetrics: Boolean
        get() = resolvedFontSize != null || resolvedLetterSpacing != null

    val isEmpty: Boolean
        get() = fill == 0 && textColor == 0 && !bold && !italic &&
            underline == null && strike == null && box == null && emphasis == null &&
            shadow == null && resolvedFontPath.isEmpty() && !changesTextMetrics && resolvedHorizontalPadding == null

    val needsPerColumnDraw: Boolean
        get() = textColor != 0 || bold || italic || underline != null || strike != null ||
            box != null || emphasis != null || shadow != null || resolvedFontPath.isNotEmpty() || changesTextMetrics ||
            resolvedHorizontalPadding != null

    fun normalized(): HighlightStyle {
        val normalizedUnderline = underline?.normalized()
        val normalizedShadow = shadow?.normalized()
        val normalizedPadding = pillPaddingScale?.let { resolvedPillPaddingScale }
        return if (normalizedUnderline === underline && normalizedShadow === shadow &&
            normalizedPadding == pillPaddingScale && resolvedFontSize == fontSize &&
            resolvedLetterSpacing == letterSpacing && resolvedHorizontalPadding == horizontalPadding
        ) {
            this
        } else {
            copy(underline = normalizedUnderline, shadow = normalizedShadow,
                pillPaddingScale = normalizedPadding, fontSize = resolvedFontSize,
                letterSpacing = resolvedLetterSpacing, horizontalPadding = resolvedHorizontalPadding)
        }
    }

    companion object {
        fun merge(base: HighlightStyle?, other: HighlightStyle): HighlightStyle {
            val current = base ?: HighlightStyle()
            return current.copy(
                fill = if (other.fill != 0) other.fill else current.fill,
                fillShape = if (other.fill != 0) other.fillShape else current.fillShape,
                pillPaddingScale = if (other.fill != 0) other.pillPaddingScale else current.pillPaddingScale,
                textColor = if (other.textColor != 0) other.textColor else current.textColor,
                bold = other.bold || current.bold,
                italic = other.italic || current.italic,
                underline = other.underline ?: current.underline,
                strike = other.strike ?: current.strike,
                box = other.box ?: current.box,
                emphasis = other.emphasis ?: current.emphasis,
                shadow = other.shadow ?: current.shadow,
                fontPath = other.resolvedFontPath.ifEmpty { current.resolvedFontPath },
                fontSize = other.resolvedFontSize ?: current.resolvedFontSize,
                letterSpacing = other.resolvedLetterSpacing ?: current.resolvedLetterSpacing,
                horizontalPadding = other.resolvedHorizontalPadding ?: current.resolvedHorizontalPadding
            )
        }
    }
}
