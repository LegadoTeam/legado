package io.legado.app.ui.book.read.page

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.Magnifier
import androidx.annotation.RequiresApi
import io.legado.app.R
import io.legado.app.constant.PageAnim
import io.legado.app.data.entities.BookProgress
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.ContentEditDialog
import io.legado.app.ui.book.read.page.api.DataSource
import io.legado.app.ui.book.read.page.delegate.CoverPageDelegate
import io.legado.app.ui.book.read.page.delegate.HorizontalPageDelegate
import io.legado.app.ui.book.read.page.delegate.NoAnimPageDelegate
import io.legado.app.ui.book.read.page.delegate.PageDelegate
import io.legado.app.ui.book.read.page.delegate.ScrollPageDelegate
import io.legado.app.ui.book.read.page.delegate.SimulationPageDelegate
import io.legado.app.ui.book.read.page.delegate.SlidePageDelegate
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.TextPos
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.LayoutProgressListener
import io.legado.app.ui.book.read.page.provider.TextPageFactory
import io.legado.app.utils.activity
import io.legado.app.utils.invisible
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.throttle
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs

internal enum class PullBookmarkGestureState {
    NONE, PULLING, READY
}

private enum class ReplacePreviewGestureState {
    IDLE, PENDING, ACTIVE, CONSUMED
}

internal fun movedBeyondTouchSlop(
    startX: Float,
    startY: Float,
    x: Float,
    y: Float,
    touchSlop: Int,
): Boolean {
    return abs(x - startX) > touchSlop || abs(y - startY) > touchSlop
}

internal fun classifyPullBookmarkGesture(
    deltaX: Float,
    deltaY: Float,
    touchSlop: Int,
    triggerDistance: Int,
): PullBookmarkGestureState {
    if (deltaY <= touchSlop || deltaY <= abs(deltaX)) {
        return PullBookmarkGestureState.NONE
    }
    return if (deltaY >= triggerDistance) {
        PullBookmarkGestureState.READY
    } else {
        PullBookmarkGestureState.PULLING
    }
}

internal fun resolvePullBookmarkDistance(configuredDistance: Int, touchSlop: Int): Int {
    return configuredDistance.takeIf { it > 0 } ?: touchSlop * 6
}

internal fun resolvePullBookmarkPageOffset(deltaY: Float, viewHeight: Int): Float {
    return (deltaY * 0.5f).coerceIn(0f, viewHeight.coerceAtLeast(0) * 0.35f)
}

internal fun visibleParagraphRange(
    lineIndex: Int,
    lineCount: Int,
    isParagraphEnd: (Int) -> Boolean,
): IntRange {
    var start = lineIndex
    while (start > 0 && !isParagraphEnd(start - 1)) {
        start--
    }
    var end = lineIndex
    while (end < lineCount - 1 && !isParagraphEnd(end)) {
        end++
    }
    return start..end
}

internal fun selectableParagraphRange(
    paragraphRange: IntRange,
    hasColumns: (Int) -> Boolean,
): IntRange? {
    val first = paragraphRange.firstOrNull(hasColumns) ?: return null
    val last = (paragraphRange.last downTo paragraphRange.first)
        .firstOrNull(hasColumns) ?: return null
    return first..last
}

internal fun firstParagraphSelectionColumnIndex(
    columnCount: Int,
    textAt: (Int) -> String?,
): Int = (0 until columnCount)
    .firstOrNull { textAt(it)?.isBlank() != true }
    ?: 0

/**
 * 阅读视图
 */
class ReadView(context: Context, attrs: AttributeSet) :
    FrameLayout(context, attrs),
    DataSource, LayoutProgressListener {

    val callBack: CallBack get() = activity as CallBack
    var pageFactory: TextPageFactory = TextPageFactory(this)
    var pageDelegate: PageDelegate? = null
        private set(value) {
            field?.onDestroy()
            field = null
            field = value
            upContent()
        }
    override var isScroll = false
    val prevPage by lazy { PageView(context) }
    val curPage by lazy { PageView(context) }
    val nextPage by lazy { PageView(context) }
    val defaultAnimationSpeed = 300
    private var pressDown = false
    private var isMove = false
    private val readPositionVersion = ReadPositionVersion()

    //起始点
    var startX: Float = 0f
    var startY: Float = 0f

    //上一个触碰点
    var lastX: Float = 0f
    var lastY: Float = 0f

    //触碰点
    var touchX: Float = 0f
    var touchY: Float = 0f

    //是否停止动画动作
    var isAbortAnim = false

    //长按
    private var longPressed = false
    private val longPressTimeout = 600L
    private val longPressRunnable = Runnable {
        longPressed = true
        onLongPress()
    }
    private var replacePreviewGestureState = ReplacePreviewGestureState.IDLE
    private val replacePreviewPointerIds = IntArray(2) { -1 }
    private val replacePreviewStartX = FloatArray(2)
    private val replacePreviewStartY = FloatArray(2)
    private var replacePreview: ReadBook.ReplacePreview? = null
    private var replacePreviewRestorePosition: Int? = null
    private val replacePreviewRunnable = Runnable {
        if (replacePreviewGestureState == ReplacePreviewGestureState.PENDING) {
            replacePreviewGestureState = ReplacePreviewGestureState.ACTIVE
            callBack.setReplacePreview(true)
        }
    }
    var isTextSelected = false
    private var pressOnTextSelected = false
    private val initialTextPos = TextPos(0, 0, 0)
    private var textMagnifier: SelectionMagnifierApi28? = null
    private val locationOnScreen = IntArray(2)

    private val slopSquare by lazy { ViewConfiguration.get(context).scaledTouchSlop }
    private val pullBookmarkDistance
        get() = resolvePullBookmarkDistance(AppConfig.pullBookmarkDistance, slopSquare)
    private var pullBookmarkCandidate = false
    private var pullBookmarkState = PullBookmarkGestureState.NONE
    private var pullBookmarkAnimator: ValueAnimator? = null
    private var pageSlopSquare: Int = slopSquare
    var pageSlopSquare2: Int = pageSlopSquare * pageSlopSquare
    private var pageTouchClick: Int = 0
    private val tlRect = RectF()
    private val tcRect = RectF()
    private val trRect = RectF()
    private val mlRect = RectF()
    private val mcRect = RectF()
    private val mrRect = RectF()
    private val blRect = RectF()
    private val bcRect = RectF()
    private val brRect = RectF()
    private val boundary by lazy { BreakIterator.getWordInstance(Locale.getDefault()) }
    private val upProgressThrottle = throttle(200) { post { upProgress() } }
    val autoPager = AutoPager(this)
    val isAutoPage get() = autoPager.isRunning

    init {
        if (!isInEditMode) {
            upBg()
            setWillNotDraw(false)
            upPageAnim()
            upPageSlopSquare()
        }
        addView(nextPage)
        addView(curPage)
        addView(prevPage)
        prevPage.invisible()
        nextPage.invisible()
        curPage.markAsMainView()
        upPageTouchClick()
    }

    private fun setRect9x() {
        tlRect.set(0f + pageTouchClick, 0f, width * 0.33f, height * 0.33f)
        tcRect.set(width * 0.33f, 0f, width * 0.66f, height * 0.33f)
        trRect.set(width * 0.36f, 0f, width.toFloat() - pageTouchClick, height * 0.33f)
        mlRect.set(0f + pageTouchClick, height * 0.33f, width * 0.33f, height * 0.66f)
        mcRect.set(width * 0.33f, height * 0.33f, width * 0.66f, height * 0.66f)
        mrRect.set(width * 0.66f, height * 0.33f, width.toFloat() - pageTouchClick, height * 0.66f)
        blRect.set(0f + pageTouchClick, height * 0.66f, width * 0.33f, height.toFloat())
        bcRect.set(width * 0.33f, height * 0.66f, width * 0.66f, height.toFloat())
        brRect.set(width * 0.66f, height * 0.66f, width.toFloat() - pageTouchClick, height.toFloat())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setRect9x()
        prevPage.x = -w.toFloat()
        pageDelegate?.setViewSize(w, h)
        if (w > 0 && h > 0) {
            upBg()
            callBack.upSystemUiVisibility()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        pageDelegate?.onDraw(canvas)
        autoPager.onDraw(canvas)
    }

    override fun computeScroll() {
        pageDelegate?.computeScroll()
        autoPager.computeOffset()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        return true
    }

    /**
     * 触摸事件
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (replacePreviewGestureState != ReplacePreviewGestureState.IDLE) {
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                finishReplacePreviewGesture(ReplacePreviewGestureState.CONSUMED)
                return true
            }
            handleReplacePreviewGesture(event)
            return true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = this.rootWindowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.mandatorySystemGestures()
            )
            val height = activity?.windowManager?.currentWindowMetrics?.bounds?.height()
            if (height != null) {
                if (event.y > height.minus(insets.bottom)
                    && event.action != MotionEvent.ACTION_UP
                    && event.action != MotionEvent.ACTION_CANCEL
                ) {
                    return true
                }
            }
        }

        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            if (startReplacePreviewGesture(event)) return true
        }

        //在多点触控时，事件不走ACTION_DOWN分支而产生的特殊事件处理
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN ||
            event.actionMasked == MotionEvent.ACTION_POINTER_UP
        ) {
            resetPullBookmarkGesture(animatePage = false)
            pageDelegate?.onTouch(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                finishReplacePreviewGesture(ReplacePreviewGestureState.IDLE)
                resetPullBookmarkGesture(animatePage = false)
                dismissTextMagnifier()
                callBack.screenOffTimerStart()
                if (isTextSelected) {
                    curPage.cancelSelect()
                    isTextSelected = false
                    pressOnTextSelected = true
                } else {
                    pressOnTextSelected = false
                }
                longPressed = false
                postDelayed(longPressRunnable, longPressTimeout)
                pressDown = true
                isMove = false
                pullBookmarkCandidate = AppConfig.pullToToggleBookmark &&
                        !pressOnTextSelected && !isAutoPage &&
                        (!isScroll || curPage.isAtChapterTop())
                pageDelegate?.onTouch(event)
                pageDelegate?.onDown()
                setStartPoint(event.x, event.y, false)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!pressDown) return true
                val absX = abs(startX - event.x)
                val absY = abs(startY - event.y)
                if (pullBookmarkCandidate || pullBookmarkState != PullBookmarkGestureState.NONE) {
                    val state = classifyPullBookmarkGesture(
                        event.x - startX,
                        event.y - startY,
                        slopSquare,
                        pullBookmarkDistance,
                    )
                    if (pullBookmarkState != PullBookmarkGestureState.NONE ||
                        state != PullBookmarkGestureState.NONE
                    ) {
                        pullBookmarkState = if (state == PullBookmarkGestureState.NONE) {
                            PullBookmarkGestureState.PULLING
                        } else {
                            state
                        }
                        isMove = true
                        longPressed = false
                        removeCallbacks(longPressRunnable)
                        setPullBookmarkPageOffset(
                            resolvePullBookmarkPageOffset(event.y - startY, height)
                        )
                        return true
                    }
                    if (absX > slopSquare || absY > slopSquare) {
                        pullBookmarkCandidate = false
                    }
                }
                if (!isMove) {
                    isMove = absX > slopSquare || absY > slopSquare
                }
                if (isMove) {
                    longPressed = false
                    removeCallbacks(longPressRunnable)
                    if (isTextSelected) {
                        selectText(event.x, event.y)
                        showTextMagnifier(event.x, event.y)
                    } else {
                        pageDelegate?.onTouch(event)
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                dismissTextMagnifier()
                callBack.screenOffTimerStart()
                removeCallbacks(longPressRunnable)
                if (!pressDown) return true
                pressDown = false
                if (pullBookmarkState != PullBookmarkGestureState.NONE) {
                    val toggleBookmark = classifyPullBookmarkGesture(
                        event.x - startX,
                        event.y - startY,
                        slopSquare,
                        pullBookmarkDistance,
                    ) == PullBookmarkGestureState.READY
                    resetPullBookmarkGesture()
                    pressOnTextSelected = false
                    if (toggleBookmark) {
                        callBack.toggleBookmark()
                    }
                    return true
                }
                resetPullBookmarkGesture()
                if (!pageDelegate!!.isMoved && !isMove) {
                    if (!longPressed && !pressOnTextSelected) {
                        if (!curPage.onClick(startX, startY)) {
                            onSingleTapUp()
                        }
                        return true
                    }
                }
                if (isTextSelected) {
                    callBack.showTextActionMenu()
                } else if (pageDelegate!!.isMoved) {
                    pageDelegate?.onTouch(event)
                }
                pressOnTextSelected = false
            }

            MotionEvent.ACTION_CANCEL -> {
                dismissTextMagnifier()
                removeCallbacks(longPressRunnable)
                if (!pressDown) return true
                pressDown = false
                val wasPullingBookmark =
                    pullBookmarkState != PullBookmarkGestureState.NONE
                resetPullBookmarkGesture()
                if (wasPullingBookmark) {
                    pressOnTextSelected = false
                    autoPager.resume()
                    return true
                }
                if (isTextSelected) {
                    callBack.showTextActionMenu()
                } else if (pageDelegate!!.isMoved) {
                    pageDelegate?.onTouch(event)
                }
                pressOnTextSelected = false
                autoPager.resume()
            }
        }
        return true
    }

    private fun startReplacePreviewGesture(event: MotionEvent): Boolean {
        if (!AppConfig.twoFingerReplacePreview ||
            event.pointerCount != 2 ||
            !pressDown ||
            longPressed ||
            pressOnTextSelected ||
            isTextSelected ||
            isMove ||
            pageDelegate?.isMoved == true ||
            isAutoPage
        ) {
            return false
        }
        removeCallbacks(longPressRunnable)
        resetPullBookmarkGesture()
        pageDelegate?.abortAnim()
        for (index in 0..1) {
            replacePreviewPointerIds[index] = event.getPointerId(index)
            replacePreviewStartX[index] = event.getX(index)
            replacePreviewStartY[index] = event.getY(index)
        }
        replacePreviewGestureState = ReplacePreviewGestureState.PENDING
        isMove = true
        postDelayed(replacePreviewRunnable, longPressTimeout)
        return true
    }

    private fun handleReplacePreviewGesture(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (replacePreviewGestureState == ReplacePreviewGestureState.PENDING &&
                    replacePreviewPointersMoved(event)
                ) {
                    finishReplacePreviewGesture(ReplacePreviewGestureState.CONSUMED)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                finishReplacePreviewGesture(ReplacePreviewGestureState.CONSUMED)
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                finishReplacePreviewGesture(ReplacePreviewGestureState.IDLE)
                pressDown = false
                pressOnTextSelected = false
                resetPullBookmarkGesture()
            }
        }
    }

    private fun replacePreviewPointersMoved(event: MotionEvent): Boolean {
        if (event.pointerCount != 2) return true
        for (pointer in 0..1) {
            val index = event.findPointerIndex(replacePreviewPointerIds[pointer])
            if (index < 0 || movedBeyondTouchSlop(
                    replacePreviewStartX[pointer],
                    replacePreviewStartY[pointer],
                    event.getX(index),
                    event.getY(index),
                    slopSquare,
                )
            ) {
                return true
            }
        }
        return false
    }

    private fun finishReplacePreviewGesture(nextState: ReplacePreviewGestureState) {
        val wasActive = replacePreviewGestureState == ReplacePreviewGestureState.ACTIVE
        removeCallbacks(replacePreviewRunnable)
        replacePreviewGestureState = nextState
        if (wasActive) callBack.setReplacePreview(false)
        clearReplacePreview()
    }

    private fun clearReplacePreview() {
        val preview = replacePreview ?: return
        val stillCurrent = ReadBook.isCurrentReplacePreview(preview)
        replacePreview = null
        replacePreviewRestorePosition = preview.sourcePosition.takeIf { stillCurrent }
        upContent(resetPageOffset = true)
        preview.previewChapter.cancelLayout()
    }

    fun showReplacePreview(preview: ReadBook.ReplacePreview): Boolean {
        if (replacePreviewGestureState != ReplacePreviewGestureState.ACTIVE ||
            !ReadBook.isCurrentReplacePreview(preview)
        ) {
            return false
        }
        replacePreview?.previewChapter?.cancelLayout()
        replacePreview = preview
        upContent(resetPageOffset = true)
        return true
    }

    fun cancelTouchGestures() {
        removeCallbacks(longPressRunnable)
        longPressed = false
        pressDown = false
        pressOnTextSelected = false
        resetPullBookmarkGesture(animatePage = false)
        finishReplacePreviewGesture(ReplacePreviewGestureState.IDLE)
    }

    private fun resetPullBookmarkGesture(animatePage: Boolean = true) {
        pullBookmarkCandidate = false
        pullBookmarkState = PullBookmarkGestureState.NONE
        pullBookmarkAnimator?.cancel()
        pullBookmarkAnimator = null
        val startOffset = curPage.translationY
        if (!animatePage || startOffset == 0f) {
            setPullBookmarkPageOffset(0f)
            return
        }
        pullBookmarkAnimator = ValueAnimator.ofFloat(startOffset, 0f).apply {
            duration = defaultAnimationSpeed.toLong()
            addUpdateListener {
                setPullBookmarkPageOffset(it.animatedValue as Float)
            }
            start()
        }
    }

    private fun setPullBookmarkPageOffset(offset: Float) {
        curPage.translationY = offset
    }

    fun cancelSelect(clearSearchResult: Boolean = false) {
        dismissTextMagnifier()
        if (isTextSelected) {
            curPage.cancelSelect(clearSearchResult)
            isTextSelected = false
        }
    }

    /**
     * 更新状态栏
     */
    fun upStatusBar() {
        curPage.upStatusBar()
        prevPage.upStatusBar()
        nextPage.upStatusBar()
    }

    /**
     * 保存开始位置
     */
    fun setStartPoint(x: Float, y: Float, invalidate: Boolean = true) {
        startX = x
        startY = y
        lastX = x
        lastY = y
        touchX = x
        touchY = y

        if (invalidate) {
            invalidate()
        }
    }

    /**
     * 保存当前位置
     */
    fun setTouchPoint(x: Float, y: Float, invalidate: Boolean = true) {
        lastX = touchX
        lastY = touchY
        touchX = x
        touchY = y
        if (invalidate) {
            invalidate()
        }
        pageDelegate?.onScroll()
        val offset = touchY - lastY
        touchY -= offset - offset.toInt()
    }

    fun getReadPositionVersion(): Long = readPositionVersion.snapshot()

    fun markReadPositionChanged() {
        readPositionVersion.markChanged()
    }

    /**
     * 长按选择
     */
    private fun onLongPress() {
        kotlin.runCatching {
            curPage.longPress(startX, startY) { textPos: TextPos ->
                resetPullBookmarkGesture()
                isTextSelected = true
                pressOnTextSelected = true
                initialTextPos.upData(textPos)
                val startPos = textPos.copy()
                val endPos = textPos.copy()
                val page = curPage.relativePage(textPos.relativePagePos)
                if (AppConfig.longPressSelectParagraph) {
                    val range = visibleParagraphRange(
                        textPos.lineIndex,
                        page.lineSize,
                    ) { page.getLine(it).isParagraphEnd }
                    val selectableRange = selectableParagraphRange(range) {
                        page.getLine(it).columns.isNotEmpty()
                    } ?: return@longPress
                    val startLine = page.getLine(selectableRange.first)
                    startPos.lineIndex = selectableRange.first
                    startPos.columnIndex = firstParagraphSelectionColumnIndex(
                        startLine.columns.size,
                    ) { (startLine.columns[it] as? TextBaseColumn)?.charData }
                    endPos.lineIndex = selectableRange.last
                    endPos.columnIndex = page.getLine(selectableRange.last).columns.lastIndex
                    curPage.selectStartMoveIndex(startPos)
                    curPage.selectEndMoveIndex(endPos)
                    return@longPress
                }
                val stringBuilder = StringBuilder()
                var cIndex = textPos.columnIndex
                var lineStart = textPos.lineIndex
                var lineEnd = textPos.lineIndex
                for (index in textPos.lineIndex - 1 downTo 0) {
                    val textLine = page.getLine(index)
                    if (textLine.isParagraphEnd) {
                        break
                    } else {
                        stringBuilder.insert(0, textLine.text)
                        lineStart -= 1
                        cIndex += textLine.charSize
                    }
                }
                for (index in textPos.lineIndex until page.lineSize) {
                    val textLine = page.getLine(index)
                    stringBuilder.append(textLine.text)
                    lineEnd += 1
                    if (textLine.isParagraphEnd) {
                        break
                    }
                }
                var start: Int
                var end: Int
                boundary.setText(stringBuilder.toString())
                start = boundary.first()
                end = boundary.next()
                while (end != BreakIterator.DONE) {
                    if (cIndex in start until end) {
                        break
                    }
                    start = end
                    end = boundary.next()
                }
                kotlin.run {
                    var ci = 0
                    for (index in lineStart..lineEnd) {
                        val textLine = page.getLine(index)
                        for (j in textLine.columns.indices) {
                            if (ci == start) {
                                startPos.lineIndex = index
                                startPos.columnIndex = j
                            } else if (ci == end - 1) {
                                endPos.lineIndex = index
                                endPos.columnIndex = j
                                return@run
                            }
                            val column = textLine.getColumn(j)
                            ci += column.positionLength
                        }
                    }
                }
                curPage.selectStartMoveIndex(startPos)
                curPage.selectEndMoveIndex(endPos)
            }
        }
    }

    /**
     * 单击
     */
    private fun onSingleTapUp() {
        when {
            isTextSelected -> Unit
            mcRect.contains(startX, startY) -> if (!isAbortAnim) {
                click(AppConfig.clickActionMC)
            }

            bcRect.contains(startX, startY) -> {
                click(AppConfig.clickActionBC)
            }

            blRect.contains(startX, startY) -> {
                click(AppConfig.clickActionBL)
            }

            brRect.contains(startX, startY) -> {
                click(AppConfig.clickActionBR)
            }

            mlRect.contains(startX, startY) -> {
                click(AppConfig.clickActionML)
            }

            mrRect.contains(startX, startY) -> {
                click(AppConfig.clickActionMR)
            }

            tlRect.contains(startX, startY) -> {
                click(AppConfig.clickActionTL)
            }

            tcRect.contains(startX, startY) -> {
                click(AppConfig.clickActionTC)
            }

            trRect.contains(startX, startY) -> {
                click(AppConfig.clickActionTR)
            }
        }
    }

    /**
     * 点击
     */
    private fun click(action: Int) {
        when (action) {
            0 -> {
                pageDelegate?.dismissSnackBar()
                callBack.showActionMenu()
            }

            1 -> pageDelegate?.nextPageByAnim(defaultAnimationSpeed)
            2 -> pageDelegate?.prevPageByAnim(defaultAnimationSpeed)
            3 -> ReadBook.moveToNextChapter(true)
            4 -> ReadBook.moveToPrevChapter(upContent = true, toLast = false)
            5 -> ReadAloud.prevParagraph(context)
            6 -> ReadAloud.nextParagraph(context)
            7 -> callBack.addBookmark()
            8 -> ContentEditDialog.newInstance()?.let {
                activity?.showDialogFragment(it)
            }
            9 -> callBack.changeReplaceRuleState()
            10 -> callBack.openChapterList()
            11 -> callBack.openSearchActivity(null)
            12 -> ReadBook.syncProgress(
                { progress -> callBack.sureNewProgress(progress) },
                { context.longToastOnUi(context.getString(R.string.upload_book_success)) },
                { context.longToastOnUi(context.getString(R.string.sync_book_progress_success)) })

            13 -> {
                if (BaseReadAloudService.isPlay()) {
                    ReadAloud.pause(context)
                } else {
                    ReadAloud.resume(context)
                }
            }
        }
    }

    /**
     * 选择文本
     */
    private fun selectText(x: Float, y: Float) {
        curPage.selectText(x, y) { textPos ->
            val compare = initialTextPos.compare(textPos)
            when {
                compare > 0 -> {
                    curPage.selectStartMoveIndex(textPos)
                    curPage.selectEndMoveIndex(
                        initialTextPos.relativePagePos,
                        initialTextPos.lineIndex,
                        initialTextPos.columnIndex - 1
                    )
                }

                else -> {
                    curPage.selectStartMoveIndex(initialTextPos)
                    curPage.selectEndMoveIndex(textPos)
                }
            }
        }
    }

    private fun showTextMagnifier(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val magnifier = textMagnifier
            ?: SelectionMagnifierApi28(this).also { textMagnifier = it }
        magnifier.show(x, y)
    }

    private fun selectMoveAtRaw(x: Float, y: Float, moveStart: Boolean) {
        getLocationOnScreen(locationOnScreen)
        val localX = x - locationOnScreen[0]
        val localY = y - locationOnScreen[1]
        if (moveStart) {
            curPage.selectStartMove(localX, localY)
        } else {
            curPage.selectEndMove(localX, localY)
        }
        showTextMagnifier(localX, localY)
    }

    fun selectStartMoveAtRaw(x: Float, y: Float) {
        selectMoveAtRaw(x, y, true)
    }

    fun selectEndMoveAtRaw(x: Float, y: Float) {
        selectMoveAtRaw(x, y, false)
    }

    fun dismissTextMagnifier() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            textMagnifier?.dismiss()
        }
    }

    /**
     * 销毁事件
     */
    fun onDestroy() {
        cancelTouchGestures()
        dismissTextMagnifier()
        pageDelegate?.onDestroy()
        curPage.cancelSelect()
        invalidateTextPage()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private class SelectionMagnifierApi28(view: ReadView) {
        private val magnifier = Magnifier(view)

        fun show(x: Float, y: Float) = magnifier.show(x, y)

        fun dismiss() = magnifier.dismiss()
    }

    /**
     * 翻页动画完成后事件
     * @param direction 翻页方向
     */
    fun fillPage(direction: PageDirection): Boolean {
        if (replacePreview != null) return false
        return when (direction) {
            PageDirection.PREV -> {
                pageFactory.moveToPrev(true)
            }

            PageDirection.NEXT -> {
                pageFactory.moveToNext(true)
            }

            else -> false
        }
    }

    /**
     * 更新翻页动画
     */
    fun upPageAnim(upRecorder: Boolean = false) {
        isScroll = ReadBook.pageAnim() == 3
        ChapterProvider.upLayout()
        when (ReadBook.pageAnim()) {
            PageAnim.coverPageAnim -> if (pageDelegate !is CoverPageDelegate) {
                pageDelegate = CoverPageDelegate(this)
            }

            PageAnim.slidePageAnim -> if (pageDelegate !is SlidePageDelegate) {
                pageDelegate = SlidePageDelegate(this)
            }

            PageAnim.simulationPageAnim -> if (pageDelegate !is SimulationPageDelegate) {
                pageDelegate = SimulationPageDelegate(this)
            }

            PageAnim.scrollPageAnim -> if (pageDelegate !is ScrollPageDelegate) {
                pageDelegate = ScrollPageDelegate(this)
            }

            else -> if (pageDelegate !is NoAnimPageDelegate) {
                pageDelegate = NoAnimPageDelegate(this)
            }
        }
        (pageDelegate as? ScrollPageDelegate)?.noAnim = AppConfig.noAnimScrollPage
        if (upRecorder) {
            (pageDelegate as? HorizontalPageDelegate)?.upRecorder()
            autoPager.upRecorder()
        }
        pageDelegate?.setViewSize(width, height)
        if (isScroll) {
            curPage.setAutoPager(autoPager)
        } else {
            curPage.setAutoPager(null)
        }
        curPage.setIsScroll(isScroll)
    }

    /**
     * 更新阅读内容
     * @param relativePosition 相对位置 -1 上一页 0 当前页 1 下一页
     * @param resetPageOffset 滚动阅读是是否重置位置
     */
    override fun upContent(relativePosition: Int, resetPageOffset: Boolean) {
        post {
            curPage.setContentDescription(pageFactory.curPage.text)
        }
        if (isScroll && !isAutoPage) {
            if (relativePosition == 0) {
                curPage.setContent(
                    pageFactory.curPage,
                    resetPageOffset,
                    replacePreview?.chapterPosition
                        ?: replacePreviewRestorePosition
                        ?: ReadBook.durChapterPos,
                )
            } else {
                curPage.invalidateContentView()
            }
        } else {
            when (relativePosition) {
                -1 -> prevPage.setContent(pageFactory.prevPage)
                1 -> nextPage.setContent(pageFactory.nextPage)
                else -> {
                    curPage.setContent(pageFactory.curPage, resetPageOffset)
                    nextPage.setContent(pageFactory.nextPage)
                    prevPage.setContent(pageFactory.prevPage)
                }
            }
        }
        callBack.screenOffTimerStart()
        replacePreviewRestorePosition = null
    }

    private fun upProgress() {
        curPage.setProgress(pageFactory.curPage)
    }

    /**
     * 更新滑动距离
     */
    fun upPageSlopSquare() {
        val pageTouchSlop = AppConfig.pageTouchSlop
        this.pageSlopSquare = if (pageTouchSlop == 0) slopSquare else pageTouchSlop
        pageSlopSquare2 = this.pageSlopSquare * this.pageSlopSquare
    }

    /**
     * 更新边缘点击阈值
     */
    fun upPageTouchClick() {
        this.pageTouchClick = AppConfig.pageTouchClick
        setRect9x()
    }

    /**
     * 更新样式
     */
    fun upStyle() {
        ChapterProvider.upStyle()
        curPage.upStyle()
        prevPage.upStyle()
        nextPage.upStyle()
        if (ReadBookConfig.isNineBgImg) {
            upBg()
        }
    }

    /**
     * 更新背景
     */
    fun upBg() {
        ReadBookConfig.upBg(width, height)
        setBackgroundColor(ReadBookConfig.bgMeanColor)
        curPage.upBg()
        prevPage.upBg()
        nextPage.upBg()
    }

    /**
     * 更新背景透明度
     */
    fun upBgAlpha() {
        curPage.upBgAlpha()
        prevPage.upBgAlpha()
        nextPage.upBgAlpha()
    }

    /**
     * 更新时间信息
     */
    fun upTime() {
        curPage.upTime()
        prevPage.upTime()
        nextPage.upTime()
    }

    /**
     * 更新电量信息
     */
    fun upBattery(battery: Int) {
        curPage.upBattery(battery)
        prevPage.upBattery(battery)
        nextPage.upBattery(battery)
    }

    /**
     * 从选择位置开始朗读
     */
    suspend fun aloudStartSelect() {
        val selectStartPos = curPage.selectStartPos
        var pagePos = selectStartPos.relativePagePos
        val line = selectStartPos.lineIndex
        val column = selectStartPos.columnIndex
        while (pagePos > 0) {
            if (!ReadBook.moveToNextPage()) {
                ReadBook.moveToNextChapterAwait(false)
            }
            pagePos--
        }
        val startPos = curPage.textPage.getPosByLineColumn(line, column)
        ReadBook.readAloud(startPos = startPos)
    }

    /**
     * @return 选择的文本
     */
    fun getSelectText(): String {
        return curPage.selectedText
    }

    fun getCurVisiblePage(): TextPage {
        return curPage.getCurVisiblePage()
    }

    fun getReadPosition(): Pair<Int, TextLine>? {
        if (replacePreview != null) return null
        return curPage.getReadPosition()
    }

    fun getReadAloudPos(): Pair<Int, TextLine>? {
        if (replacePreview != null) return null
        return curPage.getReadAloudPos()
    }

    fun invalidateTextPage() {
        if (!AppConfig.optimizeRender) {
            return
        }
        pageFactory.run {
            prevPage.invalidateAll()
            curPage.invalidateAll()
            nextPage.invalidateAll()
            nextPlusPage.invalidateAll()
        }
    }

    fun onScrollAnimStart() {
        autoPager.pause()
    }

    fun onScrollAnimStop() {
        autoPager.resume()
    }

    fun onPageChange() {
        autoPager.reset()
        submitRenderTask()
    }

    fun submitRenderTask() {
        if (!AppConfig.optimizeRender) {
            return
        }
        curPage.submitRenderTask()
    }

    fun isLongScreenShot(): Boolean {
        return curPage.isLongScreenShot()
    }

    override fun onLayoutPageCompleted(index: Int, page: TextPage) {
        upProgressThrottle.invoke()
    }

    override val pageIndex: Int
        get() = replacePreview?.let {
            it.previewChapter.getPageIndexByCharIndex(it.chapterPosition)
        } ?: ReadBook.durPageIndex

    override val allowPageMove: Boolean
        get() = replacePreview == null

    override val currentChapter: TextChapter?
        get() {
            return replacePreview?.previewChapter
                ?: if (callBack.isInitFinish) ReadBook.textChapter(0) else null
        }

    override val nextChapter: TextChapter?
        get() {
            if (replacePreview != null) return null
            return if (callBack.isInitFinish) ReadBook.textChapter(1) else null
        }

    override val prevChapter: TextChapter?
        get() {
            if (replacePreview != null) return null
            return if (callBack.isInitFinish) ReadBook.textChapter(-1) else null
        }

    override fun hasNextChapter(): Boolean {
        if (replacePreview != null) return false
        return ReadBook.durChapterIndex < ReadBook.simulatedChapterSize - 1
    }

    override fun hasPrevChapter(): Boolean {
        if (replacePreview != null) return false
        return ReadBook.durChapterIndex > 0
    }

    interface CallBack {
        val isInitFinish: Boolean
        fun showActionMenu()
        fun screenOffTimerStart()
        fun showTextActionMenu()
        fun autoPageStop()
        fun openChapterList()
        fun addBookmark()
        fun toggleBookmark()
        fun changeReplaceRuleState()
        fun setReplacePreview(enabled: Boolean)
        fun openSearchActivity(searchWord: String?)
        fun upSystemUiVisibility()
        fun sureNewProgress(progress: BookProgress)
    }
}
