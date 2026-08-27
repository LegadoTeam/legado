package io.legado.app.ui.book.read.page.entities

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Paint.FontMetrics
import android.os.Build
import androidx.annotation.Keep
import io.legado.app.help.HighlightGeometry
import io.legado.app.help.PaintPool
import io.legado.app.help.book.isImage
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.UNDERLINE_MODE_DASHED
import io.legado.app.help.config.UNDERLINE_MODE_DOTTED
import io.legado.app.help.config.UNDERLINE_MODE_DOUBLE
import io.legado.app.help.config.UNDERLINE_MODE_DOUBLE_DASHED
import io.legado.app.help.config.UNDERLINE_MODE_OFF
import io.legado.app.help.config.UNDERLINE_MODE_SOLID
import io.legado.app.help.config.UNDERLINE_MODE_WAVY
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.HighlightDraw
import io.legado.app.ui.book.read.page.entities.TextPage.Companion.emptyTextPage
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.entities.column.TextHtmlColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.canvasrecorder.CanvasRecorderFactory
import io.legado.app.utils.canvasrecorder.recordIfNeededThenDraw
import io.legado.app.utils.dpToPx
import kotlin.math.ceil
import kotlin.math.max

internal fun underlineRenderBottom(
    mode: Int,
    baseline: Float,
    distancePx: Float,
    strokeWidthPx: Float,
    oneDpPx: Float,
    waveAmplitudePx: Float,
): Float {
    val strokeExtent = when (mode) {
        UNDERLINE_MODE_DOUBLE,
        UNDERLINE_MODE_DOUBLE_DASHED -> strokeWidthPx + oneDpPx
        UNDERLINE_MODE_WAVY -> strokeWidthPx / 2f + waveAmplitudePx
        else -> strokeWidthPx / 2f
    }
    return baseline + distancePx + strokeExtent
}

/**
 * 行信息
 */
@Keep
@Suppress("unused", "MemberVisibilityCanBePrivate")
data class TextLine(
    var text: String = "",
    private val textColumns: ArrayList<BaseColumn> = arrayListOf(),
    var lineTop: Float = 0f,
    var lineBase: Float = 0f,
    var lineBottom: Float = 0f,
    var indentWidth: Float = 0f,
    var paragraphNum: Int = 0,
    var chapterPosition: Int = 0,
    var pagePosition: Int = 0,
    val isTitle: Boolean = false,
    val isTitleNumber: Boolean = false,
    var isParagraphEnd: Boolean = false,
    var isImage: Boolean = false,
    var isHtml: Boolean = false,
    var startX: Float = 0f,
    var indentSize: Int = 0,
    var extraLetterSpacing: Float = 0f,
    var extraLetterSpacingOffsetX: Float = 0f,
    var wordSpacing: Float = 0f,
    var exceed: Boolean = false,
    var onlyTextColumn: Boolean = true,
    var reviewTitleOffset: Int = 0,
    var reviewTrailingInset: Float = 0f,
    var reviewTrailingPadding: Float? = null,
    var isReviewTrailingInsetApplied: Boolean = false,
    var hangingPunctuation: Boolean = false,
    var compressedPunctuation: Boolean = false,
) {

    val columns: List<BaseColumn> get() = textColumns
    val charSize: Int get() = text.length
    val lineStart: Float get() = textColumns.firstOrNull()?.start ?: 0f
    val lineEnd: Float get() = textColumns.lastOrNull()?.end ?: 0f
    val chapterIndices: IntRange get() = chapterPosition..chapterPosition + charSize
    val height: Float inline get() = lineBottom - lineTop
    val isReviewTitle get() = isTitle && !isTitleNumber
    val textPaint
        get() = when {
            isTitleNumber -> ChapterProvider.titleNumberPaint
            isTitle -> ChapterProvider.titlePaint
            else -> ChapterProvider.contentPaint
        }
    val textColor: Int
        get() = when {
            isTitleNumber -> ReadBookConfig.titleNumberTextColor
            isTitle -> ReadBookConfig.titleTextColor
            else -> ReadBookConfig.textColor
        }
    val textFontMetrics: FontMetrics
        get() = when {
            isTitleNumber -> ChapterProvider.titleNumberPaintFontMetrics
            isTitle -> ChapterProvider.titlePaintFontMetrics
            else -> ChapterProvider.contentPaintFontMetrics
        }
    val hasOverflowTextStyle: Boolean
        get() = styledColumnCount > 0 && textColumns.any {
            (it as? TextBaseColumn)?.highlightStyle?.let { style ->
                style.shadow != null || style.resolvedFontPath.isNotEmpty()
            } == true
        }
    val canvasRecorder = CanvasRecorderFactory.create()
    var searchResultColumnCount = 0
    var fillColumnCount = 0
    var styledColumnCount = 0
    var isReadAloud: Boolean = false
        set(value) {
            if (field != value) {
                invalidate()
            }
            if (value) {
                textPage.hasReadAloudSpan = true
            }
            field = value
        }
    var textPage: TextPage = emptyTextPage
    var isLeftLine = true

    fun addColumn(column: BaseColumn) {
        if (column !is TextColumn) {
            onlyTextColumn = false
        }
        column.textLine = this
        textColumns.add(column)
    }

    fun removeColumns(predicate: (BaseColumn) -> Boolean): Boolean {
        val removed = textColumns.removeAll(predicate)
        if (removed) {
            onlyTextColumn = textColumns.all { it is TextColumn }
        }
        return removed
    }

    fun addColumns(columns: Collection<BaseColumn>) {
        onlyTextColumn = false
        columns.forEach { column ->
            column.textLine = this
        }
        textColumns.addAll(columns)
    }

    fun getColumn(index: Int): BaseColumn {
        return textColumns.getOrElse(index) {
            textColumns.last()
        }
    }

    fun getColumnReverseAt(index: Int, offset: Int = 0): BaseColumn {
        return textColumns[textColumns.lastIndex - offset - index]
    }

    fun getColumnsCount(): Int {
        return textColumns.size
    }

    fun upTopBottom(durY: Float, textHeight: Float, fontMetrics: FontMetrics) {
        lineTop = ChapterProvider.paddingTop + durY
        lineBottom = lineTop + textHeight
        lineBase = lineBottom - fontMetrics.descent
    }

    fun isTouch(x: Float, y: Float, relativeOffset: Float): Boolean {
        return y > lineTop + relativeOffset
                && y < lineBottom + relativeOffset
                && x >= lineStart
                && x <= lineEnd + 20.dpToPx()
    }

    fun isTouchY(y: Float, relativeOffset: Float): Boolean {
        return y > lineTop + relativeOffset
                && y < lineBottom + relativeOffset
    }

    fun isVisible(relativeOffset: Float): Boolean {
        val top = lineTop + relativeOffset
        val bottom = lineBottom + relativeOffset
        val width = bottom - top
        val visibleTop = ChapterProvider.paddingTop
        val visibleBottom = ChapterProvider.visibleBottom
        val visible = when {
            // 完全可视
            top >= visibleTop && bottom <= visibleBottom -> true
            top <= visibleTop && bottom >= visibleBottom -> true
            // 上方第一行部分可视
            top < visibleTop && bottom > visibleTop && bottom < visibleBottom -> {
                if (isImage) {
                    true
                } else {
                    val visibleRate = (bottom - visibleTop) / width
                    visibleRate > 0.6
                }
            }
            // 下方第一行部分可视
            top > visibleTop && top < visibleBottom && bottom > visibleBottom -> {
                if (isImage) {
                    true
                } else {
                    val visibleRate = (visibleBottom - top) / width
                    visibleRate > 0.6
                }
            }
            // 不可视
            else -> false
        }
        return visible
    }

    fun draw(view: ContentTextView, canvas: Canvas) {
        if (AppConfig.optimizeRender && !hasOverflowTextStyle) {
            canvasRecorder.recordIfNeededThenDraw(canvas, view.width, renderedHeight()) {
                drawTextLine(view, this)
            }
        } else {
            drawTextLine(view, canvas)
        }
    }

    fun renderBottom(): Float = lineTop + renderedHeight()

    private fun renderedHeight(): Int {
        val baseline = lineBase - lineTop
        var bottom = height
        activeUnderlineMode()?.let { mode ->
            bottom = max(
                bottom,
                underlineRenderBottom(
                    mode = mode,
                    baseline = baseline,
                    distancePx = ReadBookConfig.underlineDistance.dpToPx(),
                    strokeWidthPx = ReadBookConfig.underlineWidth.dpToPx(),
                    oneDpPx = 1f.dpToPx(),
                    waveAmplitudePx = ReadBookConfig.underlineWidth.dpToPx(),
                )
            )
        }
        bottom = max(bottom, highlightUnderlineRenderBottom())
        return ceil(bottom).toInt()
    }

    private fun highlightUnderlineRenderBottom(): Float {
        val baseline = lineBase - lineTop
        return columns.asSequence()
            .mapNotNull { (it as? TextBaseColumn)?.highlightStyle?.underline?.normalized() }
            .filter { it.width > 0f }
            .map {
                HighlightGeometry.underlineRenderBottom(
                    baseline = baseline,
                    distancePx = it.distance.dpToPx(),
                    strokeWidthPx = it.width.dpToPx(),
                    kind = it.kind
                )
            }
            .maxOrNull() ?: height
    }

    private fun activeUnderlineMode(): Int? {
        val mode = ReadBookConfig.underlineMode
        if (mode == UNDERLINE_MODE_OFF) return null
        val enabled = if (isTitle) {
            ReadBookConfig.underlineTitleEnabled
        } else {
            ReadBookConfig.underlineBodyEnabled
        }
        return mode.takeIf {
            enabled && ReadBookConfig.underlineWidth > 0f &&
                !isImage && !isHtml && ReadBook.book?.isImage != true
        }
    }

    private fun drawTextLine(view: ContentTextView, canvas: Canvas) {
        if (fillColumnCount > 0) {
            drawHighlightFills(canvas)
        }
        if (checkFastDraw()) {
            fastDrawTextLine(view, canvas)
        } else {
            for (i in columns.indices) {
                columns[i].draw(view, canvas)
            }
            if (styledColumnCount > 0) {
                drawHighlightRuns(canvas)
            }
        }

        // 墨水屏模式下的朗读和搜索下划线
        if (AppConfig.isEInkMode && (isReadAloud || searchResultColumnCount > 0)) {
            val underlinePaint = PaintPool.obtain()
            underlinePaint.set(ChapterProvider.contentPaint)
            underlinePaint.strokeWidth = 1.dpToPx().toFloat()
            val lineY = height - 1.dpToPx()
            canvas.drawLine(lineStart + indentWidth, lineY, lineEnd, lineY, underlinePaint)
            PaintPool.recycle(underlinePaint)
        }

        activeUnderlineMode()?.let { drawUnderline(canvas, it) }
    }

    @SuppressLint("NewApi")
    private fun fastDrawTextLine(view: ContentTextView, canvas: Canvas) {
        val basePaint = textPaint
        val drawColor = if (isReadAloud) {
            ReadBookConfig.textAccentColor
        } else {
            textColor
        }
        if (basePaint.color != drawColor) {
            basePaint.color = drawColor
        }
        val paint = PaintPool.obtain()
        paint.set(basePaint)
        val letterSpacing = paint.letterSpacing * paint.textSize
        val letterSpacingHalf = letterSpacing * 0.5f
        if (extraLetterSpacing != 0f) {
            paint.letterSpacing += extraLetterSpacing
        }
        if (wordSpacing != 0f) {
            paint.wordSpacing = wordSpacing
        }
        val offsetX = if (atLeastApi35) letterSpacingHalf else extraLetterSpacingOffsetX
        canvas.drawText(text, indentSize, text.length, startX + offsetX, lineBase - lineTop, paint)
        PaintPool.recycle(paint)
        for (i in columns.indices) {
            val column = columns[i] as TextColumn
            if (column.selected) {
                canvas.drawRect(column.start, 0f, column.end, height, view.selectedPaint)
            }
        }
    }

    /**
     * 绘制下划线
     */
    private fun drawUnderline(canvas: Canvas, underlineMode: Int) {
        val x0 = lineStart + indentWidth
        val x1 = lineEnd
        if (x1 <= x0) return
        val paint = PaintPool.obtain()
        paint.set(textPaint)
        paint.style = Paint.Style.STROKE
        paint.pathEffect = null
        paint.strokeWidth = ReadBookConfig.underlineWidth.dpToPx()
        paint.color = if (ReadBookConfig.underlineColorSet) {
            ReadBookConfig.underlineColor
        } else {
            textColor
        }
        paint.isAntiAlias = true
        val lineY = (lineBase - lineTop) + ReadBookConfig.underlineDistance.dpToPx()
        when (underlineMode) {
            UNDERLINE_MODE_SOLID -> canvas.drawLine(x0, lineY, x1, lineY, paint)
            UNDERLINE_MODE_DASHED -> drawDashedUnderline(canvas, x0, x1, lineY, paint)
            UNDERLINE_MODE_DOTTED -> drawDottedUnderline(canvas, x0, x1, lineY, paint)
            UNDERLINE_MODE_DOUBLE -> drawDoubleUnderline(canvas, x0, x1, lineY, paint)
            UNDERLINE_MODE_WAVY -> drawWavyUnderline(canvas, x0, x1, lineY, paint)
            UNDERLINE_MODE_DOUBLE_DASHED -> {
                val separation = paint.strokeWidth + 1f.dpToPx()
                drawDashedUnderline(canvas, x0, x1, lineY - separation / 2f, paint)
                drawDashedUnderline(canvas, x0, x1, lineY + separation / 2f, paint)
            }
        }
        PaintPool.recycle(paint)
    }

    private fun drawDashedUnderline(
        canvas: Canvas,
        x0: Float,
        x1: Float,
        y: Float,
        paint: Paint,
    ) {
        val dashPaint = PaintPool.obtain()
        dashPaint.set(paint)
        dashPaint.pathEffect = underlineDashPathEffect
        canvas.drawLine(x0, y, x1, y, dashPaint)
        PaintPool.recycle(dashPaint)
    }

    private fun drawDottedUnderline(
        canvas: Canvas,
        x0: Float,
        x1: Float,
        y: Float,
        paint: Paint,
    ) {
        val dotPaint = PaintPool.obtain()
        dotPaint.set(paint)
        dotPaint.style = Paint.Style.FILL
        dotPaint.pathEffect = null
        val radius = (paint.strokeWidth / 2f).coerceAtLeast(0.5f.dpToPx())
        val step = (radius * 2.5f).coerceAtLeast(2f.dpToPx())
        var center = x0 + radius
        while (center <= x1) {
            canvas.drawCircle(center, y, radius, dotPaint)
            center += step
        }
        PaintPool.recycle(dotPaint)
    }

    private fun drawDoubleUnderline(
        canvas: Canvas,
        x0: Float,
        x1: Float,
        y: Float,
        paint: Paint,
    ) {
        val separation = paint.strokeWidth + 1f.dpToPx()
        canvas.drawLine(x0, y - separation / 2f, x1, y - separation / 2f, paint)
        canvas.drawLine(x0, y + separation / 2f, x1, y + separation / 2f, paint)
    }

    private fun drawWavyUnderline(
        canvas: Canvas,
        x0: Float,
        x1: Float,
        y: Float,
        paint: Paint,
    ) {
        val points = HighlightGeometry.wavePoints(
            x0,
            x1,
            y,
            paint.strokeWidth.coerceAtLeast(1f.dpToPx()),
            6f.dpToPx(),
            2f.dpToPx()
        )
        if (points.size < 4) return
        val path = Path()
        path.moveTo(points[0], points[1])
        var index = 2
        while (index < points.size) {
            path.lineTo(points[index], points[index + 1])
            index += 2
        }
        canvas.drawPath(path, paint)
    }

    private fun drawHighlightFills(canvas: Canvas) {
        val baseline = lineBase - lineTop
        val baseTextSize = textPaint.textSize
        var index = 0
        while (index < columns.size) {
            val first = columns[index] as? TextBaseColumn
            val style = first?.highlightStyle
            if (first == null || style == null || style.fill == 0) {
                index++
                continue
            }
            val fill = style.fill
            val shape = style.resolvedFillShape
            val textSize = (first as? TextHtmlColumn)?.mTextSize ?: baseTextSize
            var endIndex = index + 1
            while (endIndex < columns.size) {
                val next = columns[endIndex] as? TextBaseColumn ?: break
                val nextStyle = next.highlightStyle ?: break
                val nextTextSize = (next as? TextHtmlColumn)?.mTextSize ?: baseTextSize
                if (
                    nextStyle.fill == fill &&
                    nextStyle.resolvedFillShape == shape &&
                    nextTextSize == textSize
                ) {
                    endIndex++
                } else {
                    break
                }
            }
            val last = columns[endIndex - 1] as TextBaseColumn
            val band = HighlightGeometry.fillBand(
                baseline,
                textSize,
                height,
                shape,
                1f.dpToPx()
            )
            HighlightDraw.drawFillRun(
                canvas,
                first.start,
                last.end,
                band.top,
                band.bottom,
                fill,
                shape
            )
            index = endIndex
        }
    }

    private fun drawHighlightRuns(canvas: Canvas) {
        val baseline = lineBase - lineTop
        val baseTextSize = textPaint.textSize
        val fontMetrics = textFontMetrics
        var index = 0
        while (index < columns.size) {
            val first = columns[index] as? TextBaseColumn
            val style = first?.highlightStyle
            val underline = style?.underline
            val strike = style?.strike
            val box = style?.box
            if (first == null || style == null || (underline == null && strike == null && box == null)) {
                index++
                continue
            }
            val sizeSensitive = strike != null || box != null
            val textSize = if (sizeSensitive) {
                (first as? TextHtmlColumn)?.mTextSize ?: baseTextSize
            } else {
                baseTextSize
            }
            var endIndex = index + 1
            while (endIndex < columns.size) {
                val next = columns[endIndex] as? TextBaseColumn ?: break
                val nextStyle = next.highlightStyle
                val sameTextSize = !sizeSensitive ||
                    ((next as? TextHtmlColumn)?.mTextSize ?: baseTextSize) == textSize
                if (
                    nextStyle != null &&
                    nextStyle.underline == underline &&
                    nextStyle.strike == strike &&
                    nextStyle.box == box &&
                    nextStyle.textColor == style.textColor &&
                    sameTextSize
                ) {
                    endIndex++
                } else {
                    break
                }
            }
            val last = columns[endIndex - 1] as TextBaseColumn
            val fallbackColor = style.textColor.takeIf { it != 0 } ?: textColor
            val metricScale = textSize / baseTextSize
            HighlightDraw.drawRun(
                canvas,
                first.start,
                last.end,
                baseline,
                height,
                fontMetrics.ascent * metricScale,
                fontMetrics.descent * metricScale,
                underline,
                strike,
                box,
                fallbackColor
            )
            index = endIndex
        }
    }

    fun checkFastDraw(): Boolean {
        if (!FastDrawRule.canDrawWholeLine(
                AppConfig.optimizeRender, exceed, hangingPunctuation, compressedPunctuation,
                onlyTextColumn, textPage.isMsgPage
            )
        ) {
            return false
        }
        if (wordSpacing != 0f && (!atLeastApi26 || !wordSpacingWorking)) {
            return false
        }
        return searchResultColumnCount == 0 && styledColumnCount == 0
    }

    fun invalidate() {
        invalidateSelf()
        textPage.invalidate()
    }

    fun invalidateSelf() {
        canvasRecorder.invalidate()
    }

    fun recycleRecorder() {
        canvasRecorder.recycle()
    }

    @SuppressLint("NewApi")
    companion object {
        val emptyTextLine = TextLine()
        private val atLeastApi26 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        val atLeastApi28 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        private val atLeastApi35 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
        private val underlineDashPathEffect = DashPathEffect(
            floatArrayOf(6f.dpToPx(), 4f.dpToPx()),
            0f
        )
        private val wordSpacingWorking by lazy {
            // issue 3785 3846
            val paint = PaintPool.obtain()
            val text = "一二 三"
            val width1 = paint.measureText(text)
            try {
                paint.wordSpacing = 10f
                val width2 = paint.measureText(text)
                width2 - width1 == 10f
            } catch (e: NoSuchMethodError) {
                false
            } finally {
                PaintPool.recycle(paint)
            }
        }
    }

}

/**
 * 整行一次性绘制的前置条件
 * 一次 drawText 只能按字宽顺序排字,列坐标被改写过的行必须退回逐列绘制
 */
internal object FastDrawRule {

    fun canDrawWholeLine(
        optimizeRender: Boolean,
        /**超出版心后整体左移*/
        exceed: Boolean,
        /**段首标点悬挂到缩进内*/
        hangingPunctuation: Boolean,
        /**标点挤压后列宽小于字宽*/
        compressedPunctuation: Boolean,
        onlyTextColumn: Boolean,
        isMsgPage: Boolean
    ): Boolean {
        return optimizeRender && !exceed && !hangingPunctuation && !compressedPunctuation &&
                onlyTextColumn && !isMsgPage
    }

}
