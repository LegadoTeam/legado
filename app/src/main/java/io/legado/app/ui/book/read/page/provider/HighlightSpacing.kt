package io.legado.app.ui.book.read.page.provider

import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import io.legado.app.help.HighlightGeometry
import io.legado.app.help.HighlightMatcher
import io.legado.app.help.HighlightStyle
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.ui.book.read.page.entities.column.TextHtmlColumn
import io.legado.app.utils.dpToPx
import kotlin.math.ceil

/** Extra advances only; no characters are inserted into the chapter's anchor text. */
data class HighlightSpacing(val columns: Map<Int, Insets> = emptyMap()) {
    data class Insets(
        val length: Int,
        val before: Float = 0f,
        val after: Float = 0f,
        val contentWidth: Float? = null,
        val reviewGap: Float? = null,
        val reviewWidth: Float = 0f,
    )

    operator fun get(position: Int): Insets? = columns[position]

    fun withSpans(text: CharSequence, chapterStart: Int): CharSequence {
        val entries = columns.filterKeys { it in chapterStart until chapterStart + text.length }
        if (entries.isEmpty()) return text
        return SpannableString(text).apply {
            entries.forEach { (position, inset) ->
                val start = position - chapterStart
                val end = (start + inset.length).coerceAtMost(length)
                val original = getSpans(start, end, ReplacementSpan::class.java).firstOrNull()
                original?.let(::removeSpan)
                setSpan(PaddingSpan(inset, original), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private class PaddingSpan(val inset: Insets, val original: ReplacementSpan?) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            val originalWidth = original?.getSize(paint, text, start, end, fm)?.toFloat()
            if (original == null && fm != null) paint.getFontMetricsInt(fm)
            val width = inset.contentWidth ?: originalWidth
                ?: paint.measureText(text, start, end)
            return ceil(width + inset.before + inset.after).toInt()
        }

        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float,
            top: Int, y: Int, bottom: Int, paint: Paint) {
            if (original != null) original.draw(canvas, text, start, end, x + inset.before, top, y, bottom, paint)
            else canvas.drawText(text, start, end, x + inset.before, y.toFloat(), paint)
        }
    }

    companion object {
        private data class Cell(val column: BaseColumn, val line: TextLine, val position: Int,
            val style: HighlightStyle?) {
            val textSize get() = (column as? TextHtmlColumn)?.mTextSize ?: line.textPaint.textSize
            val padding get(): Float {
                val band = HighlightGeometry.fillBand(line.lineBase - line.lineTop,
                    textSize, line.height, HighlightStyle.FillShape.PILL, 1f.dpToPx())
                return (band.bottom - band.top) / 2f * checkNotNull(style).resolvedPillPaddingScale
            }
            val advance get(): Float {
                val text = (column as? TextBaseColumn)?.charData ?: return column.end - column.start
                val paint = Paint(line.textPaint).apply { textSize = this@Cell.textSize }
                // Justification can disappear when a paragraph wraps differently.
                return minOf(column.end - column.start, paint.measureText(text))
            }
        }

        fun resolve(chapter: TextChapter, ranges: List<HighlightMatcher.Range>): HighlightSpacing {
            val result = linkedMapOf<Int, Insets>()
            val paragraphs = mutableListOf<List<Cell>>()
            var cells = mutableListOf<Cell>()
            for (page in chapter.pages) {
                val styles = HighlightMatcher.resolve(chapter.getReadLength(page.index), page.lines.map { line ->
                    HighlightMatcher.LineSpec(line.charSize, line.columns.map { it.positionLength },
                        line.isParagraphEnd, line.isTitle)
                }, ranges)
                page.lines.forEachIndexed { row, line ->
                    var position = line.chapterPosition
                    line.columns.forEachIndexed { i, column ->
                        if (column !is ReviewColumn) cells.add(Cell(column, line, position, styles[row][i]))
                        position += column.positionLength
                    }
                    if (line.isParagraphEnd) {
                        paragraphs.add(cells)
                        cells = mutableListOf()
                    }
                }
            }
            if (cells.isNotEmpty()) paragraphs.add(cells)
            // Resolve neighbours across soft line/page breaks: another padded token can move
            // an originally separated image and capsule onto the same line on the second layout.
            for (paragraph in paragraphs) {
                var i = 0
                while (i < paragraph.size) {
                    val first = paragraph[i]
                    val style = first.style
                    if (first.column !is TextBaseColumn || style == null || style.fill == 0 ||
                        style.resolvedFillShape != HighlightStyle.FillShape.PILL) { i++; continue }
                    var end = i + 1
                    while (end < paragraph.size && paragraph[end].column is TextBaseColumn &&
                        paragraph[end].style?.let { it.fill == style.fill &&
                            it.resolvedFillShape == style.resolvedFillShape &&
                            it.resolvedPillPaddingScale == style.resolvedPillPaddingScale } == true &&
                        paragraph[end].textSize == first.textSize) end++
                    // A physical pixel between the cap and image keeps their antialiased
                    // coverage disjoint even when measured advances end on fractional pixels.
                    val padding = paragraph.subList(i, end).maxOf { it.padding } + 1f
                    var distance = 0f
                    for (left in i - 1 downTo 0) {
                        val cell = paragraph[left]
                        val column = cell.column
                        if (distance >= padding) break
                        if (column is ImageColumn) {
                            val key = cell.position
                            val old = result[key] ?: Insets(column.positionLength, contentWidth = column.end - column.start)
                            result[key] = old.copy(after = maxOf(old.after, padding - distance))
                            break
                        }
                        if (column !is TextBaseColumn) break
                        distance += cell.advance
                    }
                    distance = 0f
                    for (right in end until paragraph.size) {
                        val cell = paragraph[right]
                        val column = cell.column
                        if (distance >= padding) break
                        if (column is ImageColumn) {
                            val key = cell.position
                            val old = result[key] ?: Insets(column.positionLength, contentWidth = column.end - column.start)
                            result[key] = old.copy(before = maxOf(old.before, padding - distance))
                            break
                        }
                        if (column !is TextBaseColumn) break
                        distance += cell.advance
                    }
                    val tail = paragraph.last()
                    val line = tail.line
                    if (line.isParagraphEnd && distance < padding &&
                        paragraph.subList(end, paragraph.size).all { it.column is TextBaseColumn } &&
                        ChapterProvider.getReviewCount(line.paragraphNum, line.isReviewTitle,
                            line.reviewTitleOffset, chapter.chapter.index) > 0) {
                        val gap = padding - distance
                        if (gap > 0f) {
                            val width = ChapterProvider.getReviewWidth(line.isReviewTitle)
                            val key = tail.position
                            val old = result[key] ?: Insets(tail.column.positionLength)
                            result[key] = old.copy(after = gap + width, reviewGap = gap, reviewWidth = width,
                                contentWidth = old.contentWidth ?: (tail.column.end - tail.column.start))
                        }
                    }
                    i = end
                }
            }
            return HighlightSpacing(result)
        }
    }
}
