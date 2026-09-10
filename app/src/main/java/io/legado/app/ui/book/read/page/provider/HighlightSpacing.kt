package io.legado.app.ui.book.read.page.provider

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import io.legado.app.help.HighlightMatcher
import io.legado.app.help.HighlightStyle
import io.legado.app.ui.book.read.page.HighlightDraw
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.ui.book.read.page.entities.column.TextHtmlColumn
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getTextWidthsCompat
import kotlin.math.ceil

/** Extra advances only; no characters are inserted into the chapter's anchor text. */
data class HighlightSpacing(
    val columns: Map<Int, Insets> = emptyMap(),
    val paragraphEdges: List<ParagraphEdge> = emptyList(),
) {
    data class ParagraphEdge(val start: Int, val end: Int, val padding: Float)

    val isEmpty get() = columns.isEmpty() && paragraphEdges.isEmpty()
    val hasTextMetrics = columns.values.any { it.textAscent != null }

    fun edgePadding(start: Int, end: Int): Float = paragraphEdges.asSequence()
        .filter { it.start < end && it.end > start }.maxOfOrNull { it.padding } ?: 0f

    data class Insets(
        val length: Int,
        val before: Float = 0f,
        val after: Float = 0f,
        val contentWidth: Float? = null,
        val reviewGap: Float? = null,
        val reviewWidth: Float = 0f,
        val textAscent: Float? = null,
        val textDescent: Float? = null,
        val metricStyle: HighlightStyle? = null,
    )

    operator fun get(position: Int): Insets? = columns[position]

    fun withSpans(text: CharSequence, chapterStart: Int): CharSequence {
        val entries = columns.filterKeys { it in chapterStart until chapterStart + text.length }.toSortedMap()
        if (entries.isEmpty()) return text
        return SpannableString(text).apply {
            var runStart = 0
            var runEnd = 0
            var runStyle: HighlightStyle? = null
            fun finishRun() {
                runStyle?.let { setSpan(MetricsSpan(it), runStart, runEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            }
            entries.forEach { (position, inset) ->
                val start = position - chapterStart
                val end = (start + inset.length).coerceAtMost(length)
                if (start != runEnd || inset.metricStyle != runStyle) {
                    finishRun()
                    runStart = start
                    runStyle = inset.metricStyle
                }
                runEnd = end
                if (inset.metricStyle != null && inset.before == 0f && inset.after == 0f) return@forEach
                val original = getSpans(start, end, ReplacementSpan::class.java).firstOrNull()
                original?.let(::removeSpan)
                setSpan(PaddingSpan(inset, original), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            finishRun()
        }
    }

    private class MetricsSpan(val style: HighlightStyle) : MetricAffectingSpan() {
        override fun updateMeasureState(paint: TextPaint) {
            val styled = HighlightDraw.obtainTextPaint(paint, style, paint.color, "")
            try { paint.set(styled) } finally { HighlightDraw.recycleTextPaint(styled) }
        }

        override fun updateDrawState(paint: TextPaint) = updateMeasureState(paint)
    }

    private class PaddingSpan(val inset: Insets, val original: ReplacementSpan?) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            val originalWidth = original?.getSize(paint, text, start, end, fm)?.toFloat()
            if (original == null && fm != null) paint.getFontMetricsInt(fm)
            if (fm != null) {
                inset.textAscent?.let { fm.ascent = minOf(fm.ascent, kotlin.math.floor(it).toInt()); fm.top = minOf(fm.top, fm.ascent) }
                inset.textDescent?.let { fm.descent = maxOf(fm.descent, ceil(it).toInt()); fm.bottom = maxOf(fm.bottom, fm.descent) }
            }
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
            val baseTextSize get() = (column as? TextHtmlColumn)?.mTextSize ?: line.textPaint.textSize
            val textSize get() = HighlightDraw.textSize(baseTextSize, style)
            var metrics: Insets? = null
            val padding get(): Float {
                val style = checkNotNull(style)
                val extra = (style.resolvedHorizontalPadding ?: 0f).dpToPx()
                if (style.fill == 0 || style.resolvedFillShape != HighlightStyle.FillShape.PILL) return extra
                // The full PILL band is 0.9em above and 0.16em below the baseline, plus
                // 2dp on each side. HTML fallback metrics can unclip it after reflow.
                val fullBandHeight = textSize * 1.06f + 4f.dpToPx()
                return fullBandHeight / 2f * style.resolvedPillPaddingScale + extra
            }
            val advance get(): Float {
                metrics?.contentWidth?.let { return it }
                val text = (column as? TextBaseColumn)?.charData ?: return column.end - column.start
                val paint = Paint(line.textPaint).apply { textSize = this@Cell.textSize }
                // Justification can disappear when a paragraph wraps differently.
                return minOf(column.end - column.start, paint.measureText(text))
            }
        }

        fun resolve(chapter: TextChapter, ranges: List<HighlightMatcher.Range>): HighlightSpacing {
            val result = linkedMapOf<Int, Insets>()
            val edges = mutableListOf<ParagraphEdge>()
            val paragraphs = mutableListOf<List<Cell>>()
            var cells = mutableListOf<Cell>()
            for (page in chapter.pages) {
                val styles = HighlightMatcher.resolve(chapter.getReadLength(page.index), page.lines.map { line ->
                    HighlightMatcher.LineSpec(line.charSize, line.columns.map { it.positionLength },
                        line.isParagraphEnd, line.isTitle,
                        line.columns.map { (it as? TextBaseColumn)?.isParagraphIndent == true })
                }, ranges)
                page.lines.forEachIndexed { row, line ->
                    var position = line.chapterPosition
                    line.columns.forEachIndexed { i, column ->
                        if (column !is ReviewColumn) {
                            val cell = Cell(column, line, position, styles[row][i])
                            cells.add(cell)
                        }
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
                var metricStart = 0
                while (metricStart < paragraph.size) {
                    val first = paragraph[metricStart]
                    val style = first.style
                    if (first.column !is TextBaseColumn || style?.changesTextMetrics != true) {
                        metricStart++
                        continue
                    }
                    var metricEnd = metricStart + 1
                    while (metricEnd < paragraph.size && paragraph[metricEnd].column is TextBaseColumn &&
                        paragraph[metricEnd].style == style && paragraph[metricEnd].baseTextSize == first.baseTextSize &&
                        paragraph[metricEnd].position == paragraph[metricEnd - 1].let { it.position + it.column.positionLength }) metricEnd++
                    val run = paragraph.subList(metricStart, metricEnd)
                    val text = run.joinToString("") { (it.column as TextBaseColumn).charData }
                    val base = Paint(first.line.textPaint).apply { textSize = first.baseTextSize }
                    val paint = HighlightDraw.obtainTextPaint(base, style, base.color, text)
                    try {
                        val widths = FloatArray(text.length)
                        TextPaint(paint).getTextWidthsCompat(text, widths, 0f)
                        val fm = paint.fontMetrics
                        val ink = Rect()
                        paint.getTextBounds(text, 0, text.length, ink)
                        var offset = 0
                        for (cell in run) {
                            val length = (cell.column as TextBaseColumn).charData.length
                            var width = 0f
                            repeat(length) { width += widths[offset++] }
                            val inset = Insets(cell.column.positionLength, contentWidth = width,
                                textAscent = minOf(fm.top, fm.ascent, ink.top.toFloat()),
                                textDescent = maxOf(fm.bottom, fm.descent, ink.bottom.toFloat()), metricStyle = style)
                            cell.metrics = inset
                            result[cell.position] = inset
                        }
                    } finally { HighlightDraw.recycleTextPaint(paint) }
                    metricStart = metricEnd
                }
                var i = 0
                var edgePadding = 0f
                while (i < paragraph.size) {
                    val first = paragraph[i]
                    val style = first.style
                    if (first.column !is TextBaseColumn || style == null ||
                        style.resolvedHorizontalPadding == null &&
                        (style.fill == 0 || style.resolvedFillShape != HighlightStyle.FillShape.PILL)) { i++; continue }
                    val explicit = style.resolvedHorizontalPadding != null
                    var end = i + 1
                    while (end < paragraph.size && paragraph[end].column is TextBaseColumn &&
                        paragraph[end].style?.let { if (explicit || it.resolvedHorizontalPadding != null) it == style
                        else it.fill == style.fill &&
                            it.resolvedFillShape == style.resolvedFillShape &&
                            it.resolvedPillPaddingScale == style.resolvedPillPaddingScale } == true &&
                        paragraph[end].textSize == first.textSize) end++
                    // A physical pixel between the cap and image keeps their antialiased
                    // coverage disjoint even when measured advances end on fractional pixels.
                    val padding = paragraph.subList(i, end).maxOf { it.padding } + 1f
                    edgePadding = maxOf(edgePadding, padding)
                    if (explicit) {
                        // Reserve the shape's entire footprint at actual run boundaries. Only
                        // boundary Unicode cells get spans, so interior shaping/word breaks stay intact.
                        val last = paragraph[end - 1]
                        val before = result[first.position] ?: Insets(first.column.positionLength)
                        result[first.position] = before.copy(before = maxOf(before.before, padding))
                        val after = result[last.position] ?: Insets(last.column.positionLength)
                        result[last.position] = after.copy(after = maxOf(after.after, padding))
                        val line = last.line
                        if (end == paragraph.size && line.isParagraphEnd &&
                            ChapterProvider.getReviewCount(line.paragraphNum, line.isReviewTitle,
                                line.reviewTitleOffset, chapter.chapter.index) > 0) {
                            val width = ChapterProvider.getReviewWidth(line.isReviewTitle)
                            result[last.position] = result.getValue(last.position).copy(
                                after = padding + width, reviewGap = padding, reviewWidth = width)
                        }
                        i = end
                        continue
                    }
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
                if (edgePadding > 0f) {
                    val tail = paragraph.last()
                    edges.add(ParagraphEdge(paragraph.first().position,
                        tail.position + tail.column.positionLength, edgePadding))
                }
            }
            return HighlightSpacing(result, edges)
        }
    }
}
