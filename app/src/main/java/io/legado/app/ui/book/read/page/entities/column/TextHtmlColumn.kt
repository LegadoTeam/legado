package io.legado.app.ui.book.read.page.entities.column

import android.graphics.Canvas
import android.os.Build
import android.text.TextPaint
import androidx.annotation.Keep
import io.legado.app.help.TextViewTagHandler.Companion.HR_PLACE_CHAR
import io.legado.app.help.TextViewTagHandler.Companion.HR_PLACE_STR
import io.legado.app.help.HighlightStyle
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.HighlightDraw
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.emptyTextLine
import io.legado.app.ui.book.read.page.provider.ChapterProvider

/**
 * 带html样式的文字列
 */
@Keep
data class TextHtmlColumn(
    override var start: Float,
    override var end: Float,
    override val charData: String,
    val mTextSize: Float,
    val mTextColor: Int?,
    val linkUrl: String?
) : TextBaseColumn {

    override val positionLength: Int
        get() = if (charData == HR_PLACE_STR) HR_PLACE_CHAR.length else charData.length

    override var textLine: TextLine = emptyTextLine

    private val textPaint: TextPaint by lazy {
        TextPaint(ChapterProvider.contentPaint).apply {
            textSize = mTextSize
        }
    }

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
        val y = textLine.lineBase - textLine.lineTop
        val style = highlightStyle
        val styleTextColor = style?.textColor ?: 0
        val textColor = when {
            textLine.isReadAloud || isSearchResult || linkUrl != null -> {
                ReadBookConfig.textAccentColor
            }

            styleTextColor != 0 -> styleTextColor
            else -> mTextColor ?: ReadBookConfig.textColor
        }
        textPaint.run {
            color = textColor
            isUnderlineText = linkUrl != null
        }
        val styledPaint = style?.takeIf {
            it.textColor != 0 || it.bold || it.italic || it.shadow != null ||
                it.resolvedFontPath.isNotEmpty()
        }?.let { HighlightDraw.obtainTextPaint(textPaint, it, textColor, charData) }
        drawText(canvas, y, styledPaint ?: textPaint)
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

    private fun drawText(canvas: Canvas, y: Float, textPaint: android.graphics.Paint) {
        if (charData == HR_PLACE_STR) {
            canvas.drawRect(start, 0f, end, 3f, textPaint)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val letterSpacing = textPaint.letterSpacing * textPaint.textSize
            val letterSpacingHalf = letterSpacing * 0.5f
            canvas.drawText(charData, start + letterSpacingHalf, y, textPaint)
        } else {
            canvas.drawText(charData, start, y, textPaint)
        }
    }

}
