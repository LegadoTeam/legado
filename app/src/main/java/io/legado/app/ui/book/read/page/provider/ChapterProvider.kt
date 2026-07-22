package io.legado.app.ui.book.read.page.provider

import android.graphics.Paint
import android.graphics.Paint.FontMetrics
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.os.postDelayed
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookContent
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.RealPathUtil
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.dpToPx
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isPad
import io.legado.app.utils.postEvent
import io.legado.app.utils.spToPx
import io.legado.app.utils.textHeight
import kotlinx.coroutines.CoroutineScope
import splitties.init.appCtx
import androidx.core.net.toUri

internal object ReviewColumnGeometry {
    fun start(
        textEnd: Float,
        width: Float,
        viewWidth: Int,
        isDoublePage: Boolean,
        isLeftLine: Boolean,
        edgeInset: Float,
    ): Float {
        val pageRight = if (isDoublePage && isLeftLine) {
            (viewWidth / 2).toFloat()
        } else {
            viewWidth.toFloat()
        }
        return minOf(textEnd, pageRight - edgeInset - width)
    }
}

/**
 * 解析内容生成章节和页面
 */
@Suppress("DEPRECATION", "ConstPropertyName")
object ChapterProvider {
    //用于图片字的替换
    const val srcReplaceStr = "袮" //▩▣ //这是不应该存在的汉字,会替换为祢，这个字符用来标记
    const val srcReplaceChar = '袮'
    const val srcReplacementChar = '祢'
    //用于评论按钮的替换
    const val reviewStr = "꧁"
    const val reviewChar = '꧁'
    const val indentChar = "　"

    @JvmStatic
    var viewWidth = 0
        private set

    @JvmStatic
    var viewHeight = 0
        private set

    @JvmStatic
    var paddingLeft = 0
        private set

    @JvmStatic
    var paddingTop = 0
        private set

    @JvmStatic
    var paddingRight = 0
        private set

    @JvmStatic
    var paddingBottom = 0
        private set

    @JvmStatic
    var visibleWidth = 0
        private set

    @JvmStatic
    var visibleHeight = 0
        private set

    @JvmStatic
    var visibleRight = 0
        private set

    @JvmStatic
    var visibleBottom = 0
        private set

    @JvmStatic
    var lineSpacingExtra = 0f
        private set

    @JvmStatic
    var paragraphSpacing = 0
        private set

    @JvmStatic
    var titleTopSpacing = 0
        private set

    @JvmStatic
    var titleBottomSpacing = 0
        private set

    @JvmStatic
    var indentCharWidth = 0f
        private set

    @JvmStatic
    var titlePaintTextHeight = 0f
        private set

    @JvmStatic
    var contentPaintTextHeight = 0f
        private set

    @JvmStatic
    var titlePaintFontMetrics = FontMetrics()

    @JvmStatic
    var contentPaintFontMetrics = FontMetrics()

    @JvmStatic
    var typeface: Typeface? = Typeface.DEFAULT
        private set

    @JvmStatic
    var titlePaint: TextPaint = TextPaint()

    @JvmStatic
    var contentPaint: TextPaint = TextPaint()

    @JvmStatic
    var reviewPaint: TextPaint = TextPaint()

    @JvmStatic
    var doublePage = false
        private set

    @JvmStatic
    var visibleRect = RectF()

    private val handler by lazy {
        buildMainHandler()
    }

    private var upViewSizeRunnable: Runnable? = null

    @Volatile
    private var reviewCountProvider: ((Int, Int) -> Int)? = null

    @Volatile
    private var reviewKeyProvider: ((Int, Int) -> String?)? = null

    private const val reviewTitleOffset = 1

    init {
        upStyle()
    }

    fun getTextChapterAsync(
        scope: CoroutineScope,
        book: Book,
        bookChapter: BookChapter,
        displayTitle: String,
        bookContent: BookContent,
        chapterSize: Int,
    ): TextChapter {

        val textChapter = TextChapter(
            bookChapter,
            bookChapter.index, displayTitle,
            chapterSize,
            bookContent.sameTitleRemoved,
            bookChapter.isVip,
            bookChapter.isPay,
            bookContent.effectiveReplaceRules,
            hasBodyContent = bookContent.textList.isNotEmpty(),
        ).apply {
            createLayout(scope, book, bookContent)
        }

        return textChapter
    }

    /**
     * 更新样式
     */
    fun upStyle() {
        typeface = getTypeface(ReadBookConfig.textFont)
        getPaints(typeface).let {
            titlePaint = it.first
            contentPaint = it.second
        }
        reviewPaint.color = ReadBookConfig.reviewIconColor.takeIf { it != 0 }
            ?: if (AppConfig.isNightTheme) {
                ColorUtils.lightenColor(contentPaint.color)
            } else {
                ColorUtils.darkenColor(contentPaint.color)
            }
        reviewPaint.textSize = contentPaint.textSize * 0.45f
        reviewPaint.textAlign = Paint.Align.CENTER
        reviewPaint.isAntiAlias = true
        //间距
        lineSpacingExtra = ReadBookConfig.lineSpacingExtra / 10f
        paragraphSpacing = ReadBookConfig.paragraphSpacing
        titleTopSpacing = ReadBookConfig.titleTopSpacing.dpToPx()
        titleBottomSpacing = ReadBookConfig.titleBottomSpacing.dpToPx()
        val bodyIndent = ReadBookConfig.paragraphIndent
        indentCharWidth = if (bodyIndent.isNotEmpty()) {
            var indentWidth = StaticLayout.getDesiredWidth(bodyIndent, contentPaint)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                indentWidth += contentPaint.letterSpacing * contentPaint.textSize
            }
            indentWidth / bodyIndent.length
        } else {
            0f
        }
        titlePaintTextHeight = titlePaint.textHeight
        contentPaintTextHeight = contentPaint.textHeight
        titlePaintFontMetrics = titlePaint.fontMetrics
        contentPaintFontMetrics = contentPaint.fontMetrics
        upLayout()
    }

    fun setReviewProviders(
        countProvider: ((Int, Int) -> Int)?,
        keyProvider: ((Int, Int) -> String?)?,
    ) {
        reviewCountProvider = countProvider
        reviewKeyProvider = keyProvider
        refreshReviewColumns()
    }

    fun clearReviewProviders() {
        setReviewProviders(null, null)
    }

    fun setReviewCountProvider(provider: ((Int) -> Int)?) {
        val wrappedProvider = provider?.let { count ->
            { _: Int, reviewId: Int -> count(reviewId) }
        }
        setReviewProviders(wrappedProvider, reviewKeyProvider)
    }

    fun setReviewKeyProvider(provider: ((Int) -> String?)?) {
        val wrappedProvider = provider?.let { key ->
            { _: Int, reviewId: Int -> key(reviewId) }
        }
        setReviewProviders(reviewCountProvider, wrappedProvider)
    }

    fun getReviewKeyById(
        reviewId: Int,
        chapterIndex: Int = ReadBook.durChapterIndex,
    ): String? {
        return reviewKeyProvider?.invoke(chapterIndex, reviewId)?.takeIf { it.isNotBlank() }
    }

    private fun refreshReviewColumns() {
        refreshReviewColumns(ReadBook.prevTextChapter)
        refreshReviewColumns(ReadBook.curTextChapter)
        refreshReviewColumns(ReadBook.nextTextChapter)
    }

    private fun refreshReviewColumns(textChapter: TextChapter?) {
        textChapter ?: return
        val chapterIndex = textChapter.chapter.index
        textChapter.pages.forEach { page ->
            page.lines.forEach { line ->
                val count = getReviewCount(
                    paragraphNum = line.paragraphNum,
                    isTitle = line.isTitle,
                    titleOffset = line.reviewTitleOffset,
                    chapterIndex = chapterIndex,
                )
                val shouldShow = count > 0 && line.isParagraphEnd
                var changed = false
                if (!shouldShow) {
                    changed = line.removeColumns { it is ReviewColumn }
                } else {
                    val reviewColumn =
                        line.columns.firstOrNull { it is ReviewColumn } as? ReviewColumn
                    if (reviewColumn == null) {
                        appendReviewColumnIfNeeded(line, chapterIndex = chapterIndex)
                        changed = true
                    } else {
                        if (reviewColumn.count != count) {
                            reviewColumn.count = count
                            changed = true
                        }
                        if (updateReviewColumnLayout(reviewColumn, line)) {
                            changed = true
                        }
                    }
                }
                if (changed) line.invalidate()
            }
        }
    }

    fun getReviewCount(
        paragraphNum: Int,
        isTitle: Boolean = false,
        titleOffset: Int = reviewTitleOffset,
        chapterIndex: Int = ReadBook.durChapterIndex,
    ): Int {
        val provider = reviewCountProvider ?: return 0
        if (isTitle) {
            val titleCount = provider(chapterIndex, -1)
            if (titleCount > 0) return titleCount
        }
        val reviewId = paragraphNum - titleOffset
        if (reviewId <= 0) return 0
        return provider(chapterIndex, reviewId)
    }

    fun appendReviewColumnIfNeeded(
        textLine: TextLine,
        titleOffset: Int? = null,
        chapterIndex: Int = ReadBook.durChapterIndex,
    ) {
        if (textLine.columns.any { it is ReviewColumn }) return
        val count = getReviewCount(
            paragraphNum = textLine.paragraphNum,
            isTitle = textLine.isTitle,
            titleOffset = titleOffset ?: textLine.reviewTitleOffset,
            chapterIndex = chapterIndex,
        )
        if (count <= 0) return
        val reviewColumn = ReviewColumn(start = 0f, end = 0f, count = count)
        updateReviewColumnLayout(reviewColumn, textLine)
        textLine.addColumn(reviewColumn)
    }

    private fun updateReviewColumnLayout(
        reviewColumn: ReviewColumn,
        textLine: TextLine,
    ): Boolean {
        val width = getReviewWidth(textLine.isTitle)
        val textEnd = textLine.columns.lastOrNull { it !is ReviewColumn }?.end
            ?: textLine.lineEnd
        val start = ReviewColumnGeometry.start(
            textEnd = textEnd,
            width = width,
            viewWidth = viewWidth,
            isDoublePage = doublePage,
            isLeftLine = textLine.isLeftLine,
            edgeInset = 1.dpToPx().toFloat(),
        )
        val end = start + width
        if (reviewColumn.start == start && reviewColumn.end == end) return false
        reviewColumn.start = start
        reviewColumn.end = end
        return true
    }

    fun getReviewWidth(isTitle: Boolean): Float {
        val textSize = if (isTitle) titlePaint.textSize else contentPaint.textSize
        return textSize * 0.9f
    }

    private fun getTypeface(fontPath: String): Typeface? {
        return kotlin.runCatching {
            when {
                fontPath.isContentScheme() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    appCtx.contentResolver
                        .openFileDescriptor(fontPath.toUri(), "r")!!
                        .use {
                            Typeface.Builder(it.fileDescriptor).build()
                        }
                }

                fontPath.isContentScheme() -> {
                    Typeface.createFromFile(RealPathUtil.getPath(appCtx, fontPath.toUri()))
                }

                fontPath.isNotEmpty() -> Typeface.createFromFile(fontPath)
                else -> when (AppConfig.systemTypefaces) {
                    1 -> Typeface.SERIF
                    2 -> Typeface.MONOSPACE
                    else -> Typeface.SANS_SERIF
                }
            }
        }.getOrElse {
            ReadBookConfig.textFont = ""
            ReadBookConfig.save()
            Typeface.SANS_SERIF
        } ?: Typeface.DEFAULT
    }

    private fun getPaints(typeface: Typeface?): Pair<TextPaint, TextPaint> {
        // 字体统一处理
        val bold = Typeface.create(typeface, Typeface.BOLD)
        val normal = Typeface.create(typeface, Typeface.NORMAL)
        val (titleFont, textFont) = when (ReadBookConfig.textBold) {
            1 -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    Pair(Typeface.create(typeface, 900, false), bold)
                else
                    Pair(bold, bold)
            }

            2 -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    Pair(normal, Typeface.create(typeface, 300, false))
                else
                    Pair(normal, normal)
            }

            else -> Pair(bold, normal)
        }

        //标题
        val tPaint = TextPaint()
        tPaint.color = ReadBookConfig.textColor
        tPaint.letterSpacing = ReadBookConfig.letterSpacing
        tPaint.typeface = titleFont
        tPaint.textSize = with(ReadBookConfig) { textSize + titleSize }.toFloat().spToPx()
        tPaint.isAntiAlias = true
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && AppConfig.optimizeRender) {
            tPaint.isLinearText = true
        }
        //正文
        val cPaint = TextPaint()
        cPaint.color = ReadBookConfig.textColor
        cPaint.letterSpacing = ReadBookConfig.letterSpacing
        cPaint.typeface = textFont
        cPaint.textSize = ReadBookConfig.textSize.toFloat().spToPx()
        cPaint.isAntiAlias = true
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && AppConfig.optimizeRender) {
            cPaint.isLinearText = true
        }
        return Pair(tPaint, cPaint)
    }

    /**
     * 更新View尺寸
     */
    fun upViewSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            return
        }
        if (width != viewWidth || height != viewHeight) {
            if (width == viewWidth) {
                upViewSizeRunnable = handler.postDelayed(300) {
                    upViewSizeRunnable = null
                    notifyViewSizeChange(width, height)
                }
            } else {
                notifyViewSizeChange(width, height)
            }
        } else if (upViewSizeRunnable != null) {
            handler.removeCallbacks(upViewSizeRunnable!!)
            upViewSizeRunnable = null
        }
    }

    private fun notifyViewSizeChange(width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        upLayout()
        postEvent(EventBus.UP_CONFIG, arrayListOf(5))
    }

    /**
     * 更新绘制尺寸
     */
    fun upLayout() {
        when (AppConfig.doublePageHorizontal) {
            "0" -> doublePage = false
            "1" -> doublePage = true
            "2" -> {
                doublePage = (viewWidth > viewHeight)
                        && ReadBook.pageAnim() != 3
            }

            "3" -> {
                doublePage = (viewWidth > viewHeight || appCtx.isPad)
                        && ReadBook.pageAnim() != 3
            }
        }

        if (viewWidth <= 0 || viewHeight <= 0) {
            return
        }

        paddingLeft = ReadBookConfig.paddingLeft.dpToPx()
        paddingTop = ReadBookConfig.paddingTop.dpToPx()
        paddingRight = ReadBookConfig.paddingRight.dpToPx()
        paddingBottom = ReadBookConfig.paddingBottom.dpToPx()
        visibleWidth = if (doublePage) {
            viewWidth / 2 - paddingLeft - paddingRight
        } else {
            viewWidth - paddingLeft - paddingRight
        }
        //留1dp画最后一行下划线
        visibleHeight = viewHeight - paddingTop - paddingBottom
        visibleRight = viewWidth - paddingRight
        visibleBottom = paddingTop + visibleHeight

        if (paddingLeft >= visibleRight || paddingTop >= visibleBottom) {
            AppLog.put("边距设置过大，请重新设置", toast = true)
            setFallbackLayout()
        }

        visibleRect.set( //留余，让溢出时也显示
            paddingLeft.toFloat() - 10,
            paddingTop.toFloat() - 10,
            viewWidth.toFloat(),
            visibleBottom.toFloat() + 10f.dpToPx() //下划线最远10dp
        )

    }

    private fun setFallbackLayout() {
        paddingLeft = 20.dpToPx()
        paddingTop = 5.dpToPx()
        paddingRight = 20.dpToPx()
        paddingBottom = 5.dpToPx()
        visibleWidth = if (doublePage) {
            viewWidth / 2 - paddingLeft - paddingRight
        } else {
            viewWidth - paddingLeft - paddingRight
        }
        //留1dp画最后一行下划线
        visibleHeight = viewHeight - paddingTop - paddingBottom
        visibleRight = viewWidth - paddingRight
        visibleBottom = paddingTop + visibleHeight
    }

}
