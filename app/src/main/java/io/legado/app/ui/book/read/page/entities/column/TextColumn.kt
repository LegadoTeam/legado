package io.legado.app.ui.book.read.page.entities.column

import android.graphics.Canvas
import android.os.Build
import androidx.annotation.Keep
import io.legado.app.help.HighlightStyle
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.HighlightDraw
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.emptyTextLine

/**
 * 文字列
 */
@Keep
data class TextColumn(
    override var start: Float,
    override var end: Float,
    override val charData: String,
    /**标点挤压后字形在列内的绘制偏移,裁掉的是字框内的空白*/
    val drawOffset: Float = 0f,
) : TextBaseColumn {

    override var textLine: TextLine = emptyTextLine

    override var selected: Boolean = false
        set(value) {
            if (field != value) {
                textLine.invalidate()
            }
            field = value
        }
    override var isSearchResult: Boolean = false
        set(value) {
            if (field != value) {
                textLine.invalidate()
                if (value) {
                    textLine.searchResultColumnCount++
                } else {
                    textLine.searchResultColumnCount--
                }
            }
            field = value
        }

    override var highlightStyle: HighlightStyle? = null
        set(value) {
            val normalized = value?.normalized()
            if (field != normalized) {
                val beforeFill = field?.fill?.let { it != 0 } == true
                val afterFill = normalized?.fill?.let { it != 0 } == true
                if (!beforeFill && afterFill) textLine.fillColumnCount++
                else if (beforeFill && !afterFill) textLine.fillColumnCount--
                val before = field?.needsPerColumnDraw == true
                val after = normalized?.needsPerColumnDraw == true
                if (!before && after) textLine.styledColumnCount++
                else if (before && !after) textLine.styledColumnCount--
                field = normalized
                textLine.invalidate()
            }
        }

    override fun draw(view: ContentTextView, canvas: Canvas) {
        val textPaint = textLine.textPaint
        val style = highlightStyle
        val styleTextColor = style?.textColor ?: 0
        val baseTextColor = if (textLine.isReadAloud || isSearchResult) {
            ReadBookConfig.textAccentColor
        } else {
            textLine.textColor
        }
        val textColor = when {
            textLine.isReadAloud || isSearchResult -> ReadBookConfig.textAccentColor
            styleTextColor != 0 -> styleTextColor
            else -> textLine.textColor
        }
        if (textPaint.color != baseTextColor) {
            textPaint.color = baseTextColor
        }
        val styledPaint = style?.takeIf {
            it.textColor != 0 || it.bold || it.italic || it.shadow != null ||
                it.resolvedFontPath.isNotEmpty()
        }?.let { HighlightDraw.obtainTextPaint(textPaint, it, textColor, charData) }
        val drawPaint = styledPaint ?: textPaint
        val y = textLine.lineBase - textLine.lineTop
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val letterSpacing = drawPaint.letterSpacing * drawPaint.textSize
            val letterSpacingHalf = letterSpacing * 0.5f
            canvas.drawText(charData, start + drawOffset + letterSpacingHalf, y, drawPaint)
        } else {
            canvas.drawText(charData, start + drawOffset, y, drawPaint)
        }
        styledPaint?.let(HighlightDraw::recycleTextPaint)
        style?.takeIf { it.underline == null }?.emphasis?.let {
            HighlightDraw.drawEmphasis(
                canvas,
                start,
                end,
                textLine.height,
                if (it.color != 0) it.color else textColor
            )
        }
        if (selected) {
            canvas.drawRect(start, 0f, end, textLine.height, view.selectedPaint)
        }
    }

}
