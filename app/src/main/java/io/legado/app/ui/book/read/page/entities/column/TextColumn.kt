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
import io.legado.app.ui.book.read.page.provider.ChapterProvider

/**
 * 文字列
 */
@Keep
data class TextColumn(
    override var start: Float,
    override var end: Float,
    override val charData: String,
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
            if (field != value) {
                textLine.invalidate()
                val before = field?.needsPerColumnDraw == true
                val after = value?.needsPerColumnDraw == true
                if (!before && after) textLine.styledColumnCount++
                else if (before && !after) textLine.styledColumnCount--
            }
            field = value
        }

    override fun draw(view: ContentTextView, canvas: Canvas) {
        val textPaint = if (textLine.isTitle) {
            ChapterProvider.titlePaint
        } else {
            ChapterProvider.contentPaint
        }
        val style = highlightStyle
        val styleTextColor = style?.textColor ?: 0
        val baseTextColor = if (textLine.isReadAloud || isSearchResult) {
            ReadBookConfig.textAccentColor
        } else {
            ReadBookConfig.textColor
        }
        val textColor = when {
            textLine.isReadAloud || isSearchResult -> ReadBookConfig.textAccentColor
            styleTextColor != 0 -> styleTextColor
            else -> ReadBookConfig.textColor
        }
        if (textPaint.color != baseTextColor) {
            textPaint.color = baseTextColor
        }
        val fill = style?.fill ?: 0
        if (fill != 0) {
            canvas.drawRect(start, 0f, end, textLine.height, view.highlightPaint(fill))
        }
        val styledPaint = style?.takeIf {
            it.textColor != 0 || it.bold || it.italic
        }?.let { HighlightDraw.obtainTextPaint(textPaint, it, textColor) }
        val drawPaint = styledPaint ?: textPaint
        val y = textLine.lineBase - textLine.lineTop
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val letterSpacing = drawPaint.letterSpacing * drawPaint.textSize
            val letterSpacingHalf = letterSpacing * 0.5f
            canvas.drawText(charData, start + letterSpacingHalf, y, drawPaint)
        } else {
            canvas.drawText(charData, start, y, drawPaint)
        }
        styledPaint?.let(HighlightDraw::recycleTextPaint)
        style?.emphasis?.let {
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
