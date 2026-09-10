package io.legado.app.ui.book.read.page

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.legado.app.R
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.HighlightMatcher
import io.legado.app.help.HighlightRuleMatcher
import io.legado.app.help.HighlightStyle
import io.legado.app.help.book.isOnLineTxt
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.isForBook
import io.legado.app.ui.association.OpenUrlConfirmActivity
import io.legado.app.ui.book.read.page.delegate.PageDelegate
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.TextPos
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.ButtonColumn
import io.legado.app.ui.book.read.page.entities.column.TextHtmlColumn
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.TextPageFactory
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.activity
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * 阅读内容视图
 */
class ContentTextView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var selectAble = AppConfig.textSelectAble
    val selectedPaint by lazy {
        Paint().apply {
            color = context.getCompatColor(R.color.btn_bg_press_2)
            style = Paint.Style.FILL
        }
    }
    private var callBack: CallBack
    private val visibleRect = ChapterProvider.visibleRect
    val selectStart = TextPos(0, -1, -1)
    private val selectEnd = TextPos(0, -1, -1)
    var textPage: TextPage = TextPage()
        private set
    var isMainView = false
    var longScreenshot = false
    var reverseStartCursor = false
    var reverseEndCursor = false

    //滚动参数
    private val pageFactory get() = callBack.pageFactory
    private val pageDelegate get() = callBack.pageDelegate
    private var pageOffset = 0
    private var autoPager: AutoPager? = null
    private var isScroll = false
    private val renderRunnable by lazy { Runnable { preRenderPage() } }
    private var lastClickTime = 0L
    private var doubleClick = false
    private data class HighlightTapTarget(val id: Long, val start: Int, val end: Int, val manual: Boolean)
    private var highlightTapTarget: HighlightTapTarget? = null
    private var highlightTapPage: TextPage? = null
    private var highlightTapTime = 0L
    private var highlightTapX = 0f
    private var highlightTapY = 0f
    private val highlightDoubleTapSlop = ViewConfiguration.get(context).scaledDoubleTapSlop
    internal fun cancelHighlightTap() { highlightTapTarget = null; highlightTapPage = null }
    private val pdfZoom: PdfZoom?
        get() = (parent?.parent?.parent as? ReadView)?.pdfZoom
    private var pdfRenderer: PdfZoomRenderer? = null
    internal val pdfRenderedPixelCount: Int get() = pdfRenderer?.renderedPixelCount ?: 0
    internal val pdfRenderCount: Int get() = pdfRenderer?.renderCount ?: 0
    internal val pdfRenderedPages: List<Int> get() = pdfRenderer?.renderedPages.orEmpty()

    internal fun closePdfRenderer() {
        pdfRenderer?.close()
        pdfRenderer = null
    }

    override fun onDetachedFromWindow() {
        cancelHighlightTap()
        closePdfRenderer()
        super.onDetachedFromWindow()
    }

    //绘制图片的paint
    val imagePaint by lazy {
        Paint().apply {
            isAntiAlias = AppConfig.useAntiAlias
        }
    }

    init {
        callBack = activity as CallBack
    }

    /**
     * 设置内容
     */
    fun setContent(textPage: TextPage) {
        cancelHighlightTap()
        this.textPage = textPage
        upHighlight()
        // 非滑动翻页动画需要同步重绘，不然翻页可能会出现闪烁
        if (isScroll) {
            postInvalidate()
        } else {
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!isMainView) return
        ChapterProvider.upViewSize(w, h)
        textPage.format()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        autoPager?.onDraw(canvas)
        if (longScreenshot) {
            canvas.translate(0f, scrollY.toFloat())
        }
        check(!visibleRect.isEmpty) { "visibleRect 为空" }
        // Capsule backgrounds may extend into either page margin. Keep the shared
        // visibleRect unchanged: PDF zoom and reading positions still use its bounds.
        canvas.clipRect(0f, visibleRect.top, visibleRect.right, visibleRect.bottom)
        val zoom = pdfZoom?.takeIf { it.isEnabled() && !longScreenshot }
        if (zoom != null) {
            zoom.setBounds(visibleRect)
            val saved = canvas.save()
            zoom.transform(canvas)
            drawPage(canvas)
            canvas.restoreToCount(saved)
            if (isMainView && zoom.scale > 1f) {
                val pages = mutableListOf(textPage to relativeOffset(0))
                if (callBack.isScroll && pageFactory.hasNext()) {
                    pages.add(relativePage(1) to relativeOffset(1))
                    if (pageFactory.hasNextPlus()) pages.add(relativePage(2) to relativeOffset(2))
                }
                if (!zoom.isInteracting) {
                    val renderer = pdfRenderer ?: PdfZoomRenderer(this).also { pdfRenderer = it }
                    ReadBook.book?.let { renderer.draw(canvas, it, zoom, pages) }
                }
            } else if (pdfRenderer != null) {
                closePdfRenderer()
            }
        } else {
            closePdfRenderer()
            drawPage(canvas)
        }
    }

    /**
     * 绘制页面
     */
    private fun drawPage(canvas: Canvas) {
        var relativeOffset = relativeOffset(0)
        textPage.draw(this, canvas, relativeOffset)
        if (!callBack.isScroll) return
        //滚动翻页
        if (!pageFactory.hasNext()) return
        val textPage1 = relativePage(1)
        relativeOffset += textPage.height
        textPage1.draw(this, canvas, relativeOffset)
        if (!pageFactory.hasNextPlus()) return
        relativeOffset += textPage1.height
        if (relativeOffset < ChapterProvider.visibleHeight) {
            val textPage2 = relativePage(2)
            textPage2.draw(this, canvas, relativeOffset)
        }
    }

    override fun computeScroll() {
        pageDelegate?.computeScroll()
        autoPager?.computeOffset()
    }

    /**
     * 滚动事件
     * pageOffset 向上滚动 减小 向下滚动 增大
     * pageOffset 范围 0 ~ -textPage.height 大于0为上一页，小于-textPage.height为下一页
     * 以内容显示区域顶端为界，pageOffset的绝对值为textPage上方的高度
     * pageOffset + textPage.height 为 textPage 下方的高度
     */
    fun scroll(mOffset: Int) {
        if (pageFactory.isRefreshingResources) return
        cancelHighlightTap()
        val previousOffset = pageOffset
        val previousPage = textPage
        pageOffset += mOffset
        if (longScreenshot) {
            scrollY += -mOffset
        }
        if (!pageFactory.hasPrev() && pageOffset > 0) {
            pageOffset = 0
            pageDelegate?.abortAnim()
        } else if (!pageFactory.hasNext()
            && pageOffset < 0
            && pageOffset + textPage.height < ChapterProvider.visibleHeight
        ) {
            val offset = (ChapterProvider.visibleHeight - textPage.height).toInt()
            pageOffset = min(0, offset)
            pageDelegate?.abortAnim()
        } else if (pageOffset > 0) {
            if (pageFactory.moveToPrev(true)) {
                pageOffset -= textPage.height.toInt()
            } else {
                pageOffset = 0
                pageDelegate?.abortAnim()
            }
        } else if (pageOffset < -textPage.height) {
            val height = textPage.height
            if (pageFactory.moveToNext(upContent = true)) {
                pageOffset += height.toInt()
            } else {
                pageOffset = -height.toInt()
                pageDelegate?.abortAnim()
            }
        }
        val moved = if (textPage === previousPage) pageOffset - previousOffset else mOffset
        if (!longScreenshot && moved != 0) callBack.onReadScroll(moved)
        postInvalidateOnAnimation()
    }

    fun submitRenderTask() {
        renderThread.submit(renderRunnable)
    }

    private fun preRenderPage() {
        val view = this
        var invalidate = false
        pageFactory.run {
            if (hasPrev() && prevPage.render(view)) {
                invalidate = true
            }
            if (curPage.render(view)) {
                invalidate = true
            }
            if (hasNext() && nextPage.render(view) && callBack.isScroll) {
                invalidate = true
            }
            if (hasNextPlus() && nextPlusPage.render(view) && callBack.isScroll
                && relativeOffset(2) < ChapterProvider.visibleHeight
            ) {
                invalidate = true
            }
            if (invalidate) {
                postInvalidate()
                pageDelegate?.postInvalidate()
            }
        }
    }

    /**
     * 重置滚动位置
     */
    fun resetPageOffset() {
        pageOffset = 0
    }

    fun isAtChapterTop(): Boolean {
        return textPage.index == 0 && pageOffset == 0
    }

    fun restorePageOffset(chapterPos: Int) {
        val line = textPage.lines.firstOrNull { it.chapterPosition == chapterPos }
            ?: textPage.lines.firstOrNull {
                chapterPos in it.chapterPosition..<it.chapterPosition + it.charSize
            }
            ?: return
        if (line === textPage.lines.firstOrNull()) return
        scroll((ChapterProvider.paddingTop - line.lineTop).toInt())
    }

    /**
     * 长按
     */
    fun longPress(
        x: Float,
        y: Float,
        select: (textPos: TextPos) -> Unit,
    ) {
        cancelHighlightTap()
        val highlightActionTrigger = AppConfig.highlightActionTrigger
        touch(x, y) { relativeOffset, textPos, textPage, textLine, column ->
            when (column) {
                is ImageColumn -> callBack.onImageLongPress(x, y, column.src)
                is TextColumn -> {
                    if (highlightActionTrigger == "longPress" && column.highlightStyle != null &&
                        notifyHighlightClick(column, textPos, textPage, textLine, relativeOffset)
                    ) return@touch
                    if (!selectAble) return@touch
                    column.selected = true
                    select(textPos)
                }
                is TextHtmlColumn -> {
                    if (
                        column.highlightStyle != null &&
                        (highlightActionTrigger == "longPress" ||
                            (column.linkUrl != null && highlightActionTrigger != "doubleTap")) &&
                        notifyHighlightClick(column, textPos, textPage, textLine, relativeOffset)
                    ) return@touch
                    if (!selectAble) return@touch
                    column.selected = true
                    select(textPos)
                }
            }
        }
    }

    /**
     * 单击
     * @return true:已处理, false:未处理
     */
    @Suppress("UNUSED_ANONYMOUS_PARAMETER")
    fun click(x: Float, y: Float): Boolean {
        val currentTime = System.currentTimeMillis()
        val debounceClick = currentTime - lastClickTime < 300L //300毫秒防抖和双击
        lastClickTime = currentTime
        doubleClick = if (debounceClick) {
            !doubleClick
        } else {
            false
        }
        val highlightActionTrigger = AppConfig.highlightActionTrigger
        val previousHighlightTarget = highlightTapTarget
        val previousHighlightPage = highlightTapPage
        cancelHighlightTap()
        fun highlightTap(
            column: TextBaseColumn,
            textPos: TextPos,
            page: TextPage,
            line: TextLine,
            relativeOffset: Float,
        ): Boolean {
            if (highlightActionTrigger == "doubleTap") {
                val target = highlightAt(column, textPos, page)?.let {
                    HighlightTapTarget(it.time, it.chapterPos, it.chapterPosEnd, true)
                } ?: highlightRuleAt(column, textPos, page)?.let {
                    HighlightTapTarget(it.ruleId, it.start, it.end, false)
                } ?: return false
                val now = SystemClock.uptimeMillis()
                val dx = x - highlightTapX
                val dy = y - highlightTapY
                val isDoubleTap = previousHighlightPage === page && previousHighlightTarget == target &&
                    now - highlightTapTime <= ViewConfiguration.getDoubleTapTimeout() &&
                    dx * dx + dy * dy <= highlightDoubleTapSlop * highlightDoubleTapSlop
                if (!isDoubleTap) {
                    highlightTapTarget = target
                    highlightTapPage = page
                    highlightTapTime = now
                    highlightTapX = x
                    highlightTapY = y
                    // Consume the first tap so it cannot turn the page before the second.
                    return true
                }
            }
            return notifyHighlightClick(column, textPos, page, line, relativeOffset)
        }
        var handled = false
        touch(x, y) { relativeOffset, textPos, textPage, textLine, column ->
            when (column) {
                is ButtonColumn -> {
                    context.toastOnUi("Button Pressed!")
                    handled = true
                }

                is ReviewColumn -> {
                    if (!debounceClick) {
                        callBack.onReviewClick(
                            resolveReviewId(textLine),
                            column.count,
                            textPage.chapterIndex
                        )
                    }
                    handled = true
                }

                is ImageColumn -> when (AppConfig.clickImgWay) {
                    "1" -> { //预览图片
                        activity?.showDialogFragment(PhotoDialog(column.src, isBook = true))
                        handled = true
                    }
                    "2" -> { //兼容处理
                        if (!debounceClick) {
                            if (ReadBook.book?.isOnLineTxt == true) {
                                val click = column.click
                                val src = column.src
                                if (!click.isNullOrBlank()) {
                                    callBack.clickImg(click, src)
                                    handled = true
                                } else {
                                    handled = callBack.oldClickImg(src)
                                }
                            }
                        }
                    }
                    "3" -> { //关闭
                        handled = false
                    }
                    "4" -> { //双击
                        if (doubleClick) {
                            val click = column.click
                            if (!click.isNullOrBlank()) {
                                callBack.clickImg(click, column.src)
                                handled = true
                            }
                        } else {
                            handled = true
                        }
                    }
                    else -> { //默认点击
                        if (!debounceClick) {
                            val click = column.click
                            if (!click.isNullOrBlank()) {
                                callBack.clickImg(click, column.src)
                                handled = true
                            }
                        }
                    }
                }
                is TextHtmlColumn -> {
                    val linkUrl = column.linkUrl
                    if (linkUrl != null) {
                        activity?.startActivity<OpenUrlConfirmActivity> {
                            putExtra("uri", linkUrl)
                        }
                        handled = true
                    } else if (highlightActionTrigger != "longPress" && column.highlightStyle != null) {
                        handled = highlightTap(
                            column,
                            textPos,
                            textPage,
                            textLine,
                            relativeOffset
                        )
                    }
                }

                is TextColumn -> if (highlightActionTrigger != "longPress" &&
                    column.highlightStyle != null
                ) {
                    handled = highlightTap(
                        column,
                        textPos,
                        textPage,
                        textLine,
                        relativeOffset
                    )
                }
            }
        }
        return handled
    }

    /**
     * 选择文字
     */
    fun selectText(
        x: Float,
        y: Float,
        select: (textPos: TextPos) -> Unit,
    ) {
        touchRough(x, y) { _, textPos, _, _, column ->
            if (column is TextBaseColumn) {
                column.selected = true
                select(textPos)
            }
        }
    }

    /**
     * 开始选择符移动
     */
    fun selectStartMove(x: Float, y: Float) {
        touchRough(x, y) { _, textPos, _, _, _ ->
            if (selectStart.compare(textPos) == 0) {
                return@touchRough
            }
            if (textPos.compare(selectEnd) <= 0) {
                selectStartMoveIndex(textPos)
            } else {
                touchRough(x - 2 * cursorWidth, y) { _, textPos, _, _, _ ->
                    if (textPos.compare(selectEnd) > 0) {
                        reverseStartCursor = true
                        reverseEndCursor = false
                        selectEnd.columnIndex++
                        selectStartMoveIndex(selectEnd)
                        selectEndMoveIndex(textPos)
                    }
                }
            }
        }
    }

    /**
     * 结束选择符移动
     */
    fun selectEndMove(x: Float, y: Float) {
        touchRough(x, y) { _, textPos, _, _, _ ->
            if (textPos.compare(selectEnd) == 0) {
                return@touchRough
            }
            if (textPos.compare(selectStart) >= 0) {
                selectEndMoveIndex(textPos)
            } else {
                touchRough(x + 2 * cursorWidth, y) { _, textPos, _, _, _ ->
                    if (textPos.compare(selectStart) < 0) {
                        reverseEndCursor = true
                        reverseStartCursor = false
                        selectStart.columnIndex--
                        selectEndMoveIndex(selectStart)
                        selectStartMoveIndex(textPos)
                    }
                }
            }
        }
    }

    /**
     * 触碰位置信息
     * @param touched 回调
     */
    private fun touch(
        x: Float,
        y: Float,
        touched: (
            relativeOffset: Float,
            textPos: TextPos,
            textPage: TextPage,
            textLine: TextLine,
            column: BaseColumn
        ) -> Unit
    ) {
        if (!visibleRect.contains(x, y)) return
        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
                if (!callBack.isScroll) return
                if (relativeOffset >= ChapterProvider.visibleHeight) return
            }
            val textPage = relativePage(relativePos)
            for ((lineIndex, textLine) in textPage.lines.withIndex()) {
                if (textLine.isTouch(x, y, relativeOffset)) {
                    val reviewIndex = textLine.columns.indexOfFirst {
                        it is ReviewColumn && it.isTouch(x, y, relativeOffset)
                    }
                    if (reviewIndex >= 0) {
                        touched.invoke(
                            relativeOffset,
                            TextPos(relativePos, lineIndex, reviewIndex),
                            textPage,
                            textLine,
                            textLine.columns[reviewIndex]
                        )
                        return
                    }
                    for ((charIndex, textColumn) in textLine.columns.withIndex()) {
                        if (textColumn !is ReviewColumn && textColumn.isTouch(x)) {
                            touched.invoke(
                                relativeOffset,
                                TextPos(relativePos, lineIndex, charIndex),
                                textPage, textLine, textColumn
                            )
                            return
                        }
                    }
                    return
                }
            }
        }
    }

    /**
     * 触碰位置信息
     * 文本选择专用
     * @param touched 回调
     */
    private fun touchRough(
        x: Float,
        y: Float,
        touched: (
            relativeOffset: Float,
            textPos: TextPos,
            textPage: TextPage,
            textLine: TextLine,
            column: BaseColumn
        ) -> Unit
    ) {
        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
                if (!callBack.isScroll) return
                if (relativeOffset >= ChapterProvider.visibleHeight) return
            }
            val textPage = relativePage(relativePos)
            for (lineIndex in textPage.lines.indices) {
                val textLine = textPage.getLine(lineIndex)
                if (textLine.isTouchY(y, relativeOffset)) {
                    if (textPage.doublePage) {
                        val halfWidth = width / 2
                        if (textLine.isLeftLine && x > halfWidth) {
                            continue
                        }
                        if (!textLine.isLeftLine && x < halfWidth) {
                            continue
                        }
                    }
                    val columns = textLine.columns
                    for (charIndex in columns.indices) {
                        val textColumn = columns[charIndex]
                        if (textColumn.isTouch(x)) {
                            touched.invoke(
                                relativeOffset,
                                TextPos(relativePos, lineIndex, charIndex),
                                textPage, textLine, textColumn
                            )
                            return
                        }
                    }
                    val isLast = columns.first().start < x
                    val charIndex = if (isLast) columns.lastIndex + 1 else -1
                    val textColumn = if (isLast) columns.last() else columns.first()
                    touched.invoke(
                        relativeOffset,
                        TextPos(relativePos, lineIndex, charIndex),
                        textPage, textLine, textColumn
                    )
                    return
                }
            }
        }
    }

    fun getCurVisiblePage(): TextPage {
        val visiblePage = TextPage()
        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
                if (!callBack.isScroll) break
                if (relativeOffset >= ChapterProvider.visibleHeight) break
            }
            val textPage = relativePage(relativePos)
            val lines = textPage.lines
            for (i in lines.indices) {
                val textLine = lines[i]
                if (textLine.isVisible(relativeOffset)) {
                    val visibleLine = textLine.copy().apply {
                        lineTop += relativeOffset
                        lineBottom += relativeOffset
                    }
                    visiblePage.addLine(visibleLine)
                }
            }
        }
        return visiblePage
    }

    fun getReadPosition(): Pair<Int, TextLine>? {
        if (textPage.isMsgPage) return null
        val offset = relativeOffset(0)
        val line = textPage.lines.firstOrNull { it.isVisible(offset) }
            ?: textPage.lines.lastOrNull()
            ?: return null
        return textPage.chapterIndex to line
    }

    fun getReadAloudPos(): Pair<Int, TextLine>? {
        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
                if (!callBack.isScroll) break
                if (relativeOffset >= ChapterProvider.visibleHeight) break
            }
            val textPage = relativePage(relativePos)
            val lines = textPage.lines
            for (i in lines.indices) {
                val textLine = lines[i]
                if (textLine.isVisible(relativeOffset)) {
                    val visibleLine = textLine.copy().apply {
                        lineTop += relativeOffset
                        lineBottom += relativeOffset
                    }
                    return textPage.chapterIndex to visibleLine
                }
            }
        }
        return null
    }

    /**
     * 选择开始文字
     */
    fun selectStartMoveIndex(
        relativePagePos: Int,
        lineIndex: Int,
        charIndex: Int,
    ) {
        selectStart.relativePagePos = relativePagePos
        selectStart.lineIndex = lineIndex
        selectStart.columnIndex = max(0, charIndex)
        val textLine = relativePage(relativePagePos).getLine(lineIndex)
        val textColumn = textLine.getColumn(charIndex)
        upSelectedStart(
            if (charIndex < textLine.columns.size) textColumn.start else textColumn.end,
            textLine.lineBottom + relativeOffset(relativePagePos),
            textLine.lineTop + relativeOffset(relativePagePos)
        )
        upSelectChars()
    }

    fun selectStartMoveIndex(textPos: TextPos) = textPos.run {
        selectStartMoveIndex(relativePagePos, lineIndex, columnIndex)
    }

    /**
     * 选择结束文字
     */
    fun selectEndMoveIndex(
        relativePage: Int,
        lineIndex: Int,
        charIndex: Int,
    ) {
        selectEnd.relativePagePos = relativePage
        selectEnd.lineIndex = lineIndex
        val textLine = relativePage(relativePage).getLine(lineIndex)
        selectEnd.columnIndex = min(charIndex, textLine.columns.lastIndex)
        val textColumn = textLine.getColumn(charIndex)
        upSelectedEnd(
            if (charIndex > -1) textColumn.end else textColumn.start,
            textLine.lineBottom + relativeOffset(relativePage)
        )
        upSelectChars()
    }

    fun selectEndMoveIndex(textPos: TextPos) = textPos.run {
        selectEndMoveIndex(relativePagePos, lineIndex, columnIndex)
    }

    private fun upSelectChars() {
        if (!selectStart.isSelected() && !selectEnd.isSelected()) {
            return
        }
        val last = if (callBack.isScroll) 2 else 0
        val textPos = TextPos(0, 0, 0)
        for (relativePos in 0..last) {
            textPos.relativePagePos = relativePos
            val textPage = relativePage(relativePos)
            for ((lineIndex, textLine) in textPage.lines.withIndex()) {
                textPos.lineIndex = lineIndex
                for ((charIndex, column) in textLine.columns.withIndex()) {
                    textPos.columnIndex = charIndex
                    if (column is TextBaseColumn) {
                        val compareStart = textPos.compare(selectStart)
                        val compareEnd = textPos.compare(selectEnd)
                        column.selected = compareStart >= 0 && compareEnd <= 0
                        column.isSearchResult =
                            column.selected && callBack.isSelectingSearchResult
                        if (column.isSearchResult) {
                            textPage.searchResult.add(column)
                        }
                    }
                }
            }
        }
        postInvalidate()
    }

    private fun upSelectedStart(x: Float, y: Float, top: Float) {
        callBack.run {
            upSelectedStart(x + imgBgPaddingStart, y + headerHeight, top + headerHeight)
        }
    }

    private fun upSelectedEnd(x: Float, y: Float) {
        callBack.run {
            upSelectedEnd(x + imgBgPaddingStart, y + headerHeight)
        }
    }

    fun resetReverseCursor() {
        reverseStartCursor = false
        reverseEndCursor = false
    }

    fun cancelSelect(clearSearchResult: Boolean = false) {
        val last = if (callBack.isScroll) 2 else 0
        for (relativePos in 0..last) {
            val textPage = relativePage(relativePos)
            textPage.lines.forEach { textLine ->
                textLine.columns.forEach {
                    if (it is TextBaseColumn) {
                        it.selected = false
                        if (clearSearchResult) {
                            it.isSearchResult = false
                            textPage.searchResult.remove(it)
                        }
                    }
                }
            }
        }
        selectStart.reset()
        selectEnd.reset()
        postInvalidate()
        callBack.onCancelSelect()
    }

    fun upHighlight() {
        val last = if (isScroll) 2 else 0
        for (relativePos in 0..last) {
            val page = relativePage(relativePos)
            if (page.lines.isEmpty() || page.isMsgPage) continue
            val chapter = page.getTextChapter()
            if (chapter.isTransient || !chapter.isForBook(ReadBook.book)) {
                page.lines.forEach { line ->
                    line.columns.forEach { column ->
                        if (column is TextBaseColumn) column.highlightStyle = null
                    }
                }
                continue
            }
            val pageBase = chapter.getReadLength(page.index)
            val pageLength = page.lines.sumOf {
                it.charSize + if (it.isParagraphEnd) 1 else 0
            }
            val pageEnd = pageBase + pageLength
            val chapterRanges = ReadBook.highlightRangesOfChapter(chapter)
            ReadBook.upHighlightSpacing(chapter, chapterRanges)
            val ranges = chapterRanges.filter { it.start < pageEnd && it.end > pageBase }
            val lineSpecs = page.lines.map { line ->
                HighlightMatcher.LineSpec(
                    charSize = line.charSize,
                    columnCharLengths = line.columns.map { it.positionLength },
                    isParagraphEnd = line.isParagraphEnd,
                    isTitle = line.isTitle
                )
            }
            val styles = HighlightMatcher.resolve(
                pageBase,
                lineSpecs,
                ranges
            )
            page.lines.forEachIndexed { lineIndex, line ->
                line.columns.forEachIndexed { columnIndex, column ->
                    if (column is TextBaseColumn) {
                        column.highlightStyle = styles[lineIndex][columnIndex]
                    }
                }
            }
        }
        postInvalidate()
    }

    fun getSelectedText(): String {
        val textPos = TextPos(0, 0, 0)
        val builder = StringBuilder()
        for (relativePos in selectStart.relativePagePos..selectEnd.relativePagePos) {
            val textPage = relativePage(relativePos)
            textPos.relativePagePos = relativePos
            textPage.lines.forEachIndexed { lineIndex, textLine ->
                textPos.lineIndex = lineIndex
                val lastTextColumnIndex = textLine.columns.indexOfLast {
                    it is TextBaseColumn
                }
                textLine.columns.forEachIndexed { charIndex, column ->
                    textPos.columnIndex = charIndex
                    val compareStart = textPos.compare(selectStart)
                    val compareEnd = textPos.compare(selectEnd)
                    if (column is TextBaseColumn) {
                        when {
                            compareStart == -1 -> if (
                                selectStart.columnIndex == textLine.columns.size
                                && charIndex == lastTextColumnIndex
                            ) {
                                builder.append("\n")
                            }

                            compareEnd == 1 -> if (selectEnd.columnIndex == -1 && charIndex == 0) {
                                builder.append("\n")
                            }

                            compareStart >= 0 && compareEnd <= 0 -> {
                                builder.append(column.charData)
                                if (
                                    textLine.isParagraphEnd
                                    && charIndex == lastTextColumnIndex
                                    && compareEnd != 0
                                ) {
                                    builder.append("\n")
                                }
                            }
                        }
                    }
                }
            }
        }
        return builder.toString()
    }

    fun createBookmark(): Bookmark? {
        val page = relativePage(selectStart.relativePagePos)
        page.getTextChapter().let { chapter ->
            ReadBook.book?.let { book ->
                return book.createBookMark().apply {
                    chapterIndex = page.chapterIndex
                    chapterPos = chapter.getReadLength(page.index) +
                            page.getPosByLineColumn(selectStart.lineIndex, selectStart.columnIndex)
                    chapterName = chapter.title
                    bookText = getSelectedText()
                }
            }
        }
        return null
    }

    fun createHighlight(style: HighlightStyle): BookHighlight? {
        val book = ReadBook.book ?: return null
        val startPage = relativePage(selectStart.relativePagePos)
        val endPage = relativePage(selectEnd.relativePagePos)
        if (startPage.chapterIndex != endPage.chapterIndex) return null
        val startLine = startPage.getLine(selectStart.lineIndex)
        val endLine = endPage.getLine(selectEnd.lineIndex)
        if (startLine.isTitle || endLine.isTitle) return null
        val chapter = startPage.getTextChapter()
        if (!chapter.isForBook(book)) return null
        val titleLength = chapter.layoutTitleLength.takeIf { it >= 0 } ?: return null
        val endLength = highlightSelectionEndLength(selectEnd.columnIndex) {
            endLine.getColumn(selectEnd.columnIndex).positionLength
        }
        val rawStart = chapter.getReadLength(startPage.index) +
                startPage.getPosByLineColumn(selectStart.lineIndex, selectStart.columnIndex)
        val rawEnd = chapter.getReadLength(endPage.index) +
                endPage.getPosByLineColumn(selectEnd.lineIndex, selectEnd.columnIndex) + endLength
        val bodyStart = (rawStart - titleLength).coerceAtLeast(0)
        val bodyEnd = (rawEnd - titleLength).coerceAtLeast(0)
        if (bodyEnd <= bodyStart) return null
        val bookText = getSelectedText()
        if (!bookText.hasHighlightableText()) return null
        return BookHighlight(
            bookUrl = book.bookUrl,
            chapterUrl = chapter.chapter.url,
            bookName = book.name,
            bookAuthor = book.author,
            chapterIndex = startPage.chapterIndex,
            chapterPos = bodyStart + titleLength,
            chapterPosEnd = bodyEnd + titleLength,
            layoutTitleLength = titleLength,
            chapterName = chapter.title,
            bookText = bookText
        ).apply { applyStyle(style) }
    }

    private fun notifyHighlightClick(
        column: TextBaseColumn,
        textPos: TextPos,
        page: TextPage,
        line: TextLine,
        relativeOffset: Float
    ): Boolean {
        if (AppConfig.highlightActionTrigger == "off") return false
        val x = column.start + callBack.imgBgPaddingStart
        val y = line.lineTop + relativeOffset + callBack.headerHeight
        highlightAt(column, textPos, page)?.let {
            callBack.onHighlightClick(it, x, y)
            return true
        }
        highlightRuleAt(column, textPos, page)?.let {
            callBack.onHighlightRuleClick(it.ruleId, x, y)
            return true
        }
        return false
    }

    private fun highlightAt(
        column: TextBaseColumn,
        textPos: TextPos,
        page: TextPage
    ): BookHighlight? {
        val book = ReadBook.book ?: return null
        val chapter = page.getTextChapter()
        if (!chapter.isForBook(book)) return null
        if (page.getLine(textPos.lineIndex).isTitle) return null
        val titleLength = chapter.layoutTitleLength.takeIf { it >= 0 } ?: return null
        val rawColumnStart = chapter.getReadLength(page.index) +
                page.getPosByLineColumn(textPos.lineIndex, textPos.columnIndex)
        val columnStart = (rawColumnStart - titleLength).coerceAtLeast(0)
        val columnEnd = (rawColumnStart + column.positionLength - titleLength).coerceAtLeast(0)
        return ReadBook.anchoredHighlightsOfChapter(chapter, titleLength)
            .lastOrNull { (_, anchor) ->
                highlightRangeIntersects(columnStart, columnEnd, anchor.start, anchor.end)
            }
            ?.first
    }

    private fun highlightRuleAt(
        column: TextBaseColumn,
        textPos: TextPos,
        page: TextPage
    ): HighlightRuleMatcher.RuleMatch? {
        val book = ReadBook.book ?: return null
        val chapter = page.getTextChapter()
        if (!chapter.isForBook(book)) return null
        val line = page.getLine(textPos.lineIndex)
        val columnStart = chapter.getReadLength(page.index) +
                page.getPosByLineColumn(textPos.lineIndex, textPos.columnIndex)
        val columnEnd = columnStart + column.positionLength
        return highlightRuleAtColumn(
            ReadBook.ruleMatchesOfChapter(chapter),
            columnStart,
            columnEnd,
            line.isTitle
        )
    }

    private fun relativeOffset(relativePos: Int): Float {
        return when (relativePos) {
            0 -> pageOffset.toFloat()
            1 -> pageOffset + textPage.height
            else -> pageOffset + textPage.height + pageFactory.nextPage.height
        }
    }

    fun relativePage(relativePos: Int): TextPage {
        return when (relativePos) {
            0 -> textPage
            1 -> pageFactory.nextPage
            else -> pageFactory.nextPlusPage
        }
    }

    fun setAutoPager(autoPager: AutoPager?) {
        this.autoPager = autoPager
    }

    fun setIsScroll(value: Boolean) {
        isScroll = value
    }

    override fun canScrollVertically(direction: Int): Boolean {
        return callBack.isScroll && pageFactory.hasNext()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                longScreenshot = true
                scrollY = 0
            }

            MotionEvent.ACTION_UP -> {
                longScreenshot = false
                scrollY = 0
            }
        }
        return callBack.onLongScreenshotTouchEvent(event)
    }

    companion object {
        private val renderThread by lazy {
            Executors.newSingleThreadExecutor {
                Thread(it, "TextPageRender")
            }
        }
        private val cursorWidth = 24.dpToPx()
    }

    interface CallBack {
        val headerHeight: Int
        val imgBgPaddingStart: Int
        val pageFactory: TextPageFactory
        val pageDelegate: PageDelegate?
        val isScroll: Boolean
        fun onReadScroll(offset: Int)
        var isSelectingSearchResult: Boolean
        fun upSelectedStart(x: Float, y: Float, top: Float)
        fun upSelectedEnd(x: Float, y: Float)
        fun onImageLongPress(x: Float, y: Float, src: String)
        fun onCancelSelect()
        fun onLongScreenshotTouchEvent(event: MotionEvent): Boolean
        fun oldClickImg(src: String): Boolean
        fun clickImg(click: String, src: String)
        fun onReviewClick(paragraphNum: Int, count: Int, chapterIndex: Int)
        fun onHighlightClick(highlight: BookHighlight, x: Float, y: Float)
        fun onHighlightRuleClick(ruleId: Long, x: Float, y: Float)
    }

    private fun resolveReviewId(textLine: TextLine): Int {
        if (textLine.isTitle) return -1
        val reviewId = textLine.paragraphNum - textLine.reviewTitleOffset
        return if (reviewId > 0) reviewId else textLine.paragraphNum
    }
}

internal fun highlightRangeIntersects(
    columnStart: Int,
    columnEnd: Int,
    rangeStart: Int,
    rangeEnd: Int
): Boolean = columnStart < columnEnd && rangeStart < rangeEnd &&
        columnStart < rangeEnd && columnEnd > rangeStart

internal fun highlightRuleAtColumn(
    matches: List<HighlightRuleMatcher.RuleMatch>,
    columnStart: Int,
    columnEnd: Int,
    isTitle: Boolean
): HighlightRuleMatcher.RuleMatch? = matches.lastOrNull {
    (if (isTitle) it.applyToTitle else it.applyToBody) &&
            highlightRangeIntersects(columnStart, columnEnd, it.start, it.end)
}

internal inline fun highlightSelectionEndLength(
    columnIndex: Int,
    columnLength: () -> Int
): Int = if (columnIndex < 0) 0 else columnLength()

internal fun String.hasHighlightableText(): Boolean = any { it != '\r' && it != '\n' }
