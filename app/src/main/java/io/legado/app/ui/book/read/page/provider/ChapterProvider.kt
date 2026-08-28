package io.legado.app.ui.book.read.page.provider

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Paint.FontMetrics
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
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
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.RealPathUtil
import io.legado.app.utils.SvgUtils
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
    fun centeredTop(containerHeight: Float, contentHeight: Float): Float {
        return (containerHeight - contentHeight) / 2f
    }

    fun trailingInset(width: Float, trailingPadding: Float, edgeInset: Float): Float {
        return (width + edgeInset - trailingPadding).coerceAtLeast(0f)
    }

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

    fun trailingShift(
        currentInset: Float,
        currentApplied: Boolean,
        nextInset: Float,
        nextApplied: Boolean,
    ): Float {
        return (if (currentApplied) currentInset else 0f) -
                (if (nextApplied) nextInset else 0f)
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
    var titleLineSpacingExtra = 1f
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
    var titleNumberPaintTextHeight = 0f
        private set

    @JvmStatic
    var contentPaintTextHeight = 0f
        private set

    @JvmStatic
    var titlePaintFontMetrics = FontMetrics()

    @JvmStatic
    var titleNumberPaintFontMetrics = FontMetrics()

    @JvmStatic
    var contentPaintFontMetrics = FontMetrics()

    @JvmStatic
    fun lineSpacingFor(line: TextLine): Float {
        return if (line.isTitle && !line.isTitleNumber) {
            titleLineSpacingExtra
        } else {
            lineSpacingExtra
        }
    }

    @JvmStatic
    var typeface: Typeface? = Typeface.DEFAULT
        private set

    @JvmStatic
    var titlePaint: TextPaint = TextPaint()

    @JvmStatic
    var titleNumberPaint: TextPaint = TextPaint()

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

    @Volatile
    private var reviewProviderChapterIndex: Int? = null

    private val reviewColumnLock = Any()

    private const val reviewTitleOffset = 1
    private const val reviewIconPlaceholder = "{{count}}"
    private const val reviewIconCacheMaxBytes = 1024 * 1024
    private const val reviewIconMaxAspectRatio = 4f
    private const val reviewIconMaxPageWidthRatio = 0.5f

    private data class ReviewIconCacheKey(
        val countText: String,
        val widthPx: Int,
        val heightPx: Int,
    )

    private val reviewIconBitmapCache = object :
        LruCache<ReviewIconCacheKey, Bitmap>(reviewIconCacheMaxBytes) {
        override fun sizeOf(key: ReviewIconCacheKey, value: Bitmap): Int = value.byteCount
    }
    private val reviewIconLock = Any()
    private var lastReviewIconTemplate: String? = null
    private var invalidReviewIconTemplate: String? = null
    private var lastReviewIconAspectTemplate: String? = null
    private var reviewIconAspectRatio: Float? = null

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
        saveChapterData: Boolean = true,
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
            isTransient = !saveChapterData,
        ).apply {
            createLayout(scope, book, bookContent, saveChapterData)
        }

        return textChapter
    }

    /**
     * 更新样式
     */
    fun upStyle() {
        typeface = getTypeface(ReadBookConfig.textFont)
        val titleTypeface = if (ReadBookConfig.resolvedTitleFont == ReadBookConfig.textFont) {
            typeface
        } else {
            getTypeface(ReadBookConfig.resolvedTitleFont, typeface) {
                ReadBookConfig.titleFont = ""
            }
        }
        getPaints(titleTypeface, typeface).let {
            titlePaint = it.first
            contentPaint = it.second
            titleNumberPaint = TextPaint(titlePaint).apply {
                color = ReadBookConfig.titleNumberTextColor
                textSize = (ReadBookConfig.textSize + ReadBookConfig.titleNumberSize)
                    .toFloat().spToPx()
            }
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
        titleLineSpacingExtra =
            (100 + ReadBookConfig.titleLineSpacingExtra.coerceIn(-20, 30)) / 100f
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
        titleNumberPaintTextHeight = titleNumberPaint.textHeight
        contentPaintTextHeight = contentPaint.textHeight
        titlePaintFontMetrics = titlePaint.fontMetrics
        titleNumberPaintFontMetrics = titleNumberPaint.fontMetrics
        contentPaintFontMetrics = contentPaint.fontMetrics
        upLayout()
    }

    fun setReviewProviders(
        countProvider: ((Int, Int) -> Int)?,
        keyProvider: ((Int, Int) -> String?)?,
        chapterIndex: Int? = ReadBook.durChapterIndex,
    ) {
        synchronized(reviewColumnLock) {
            reviewCountProvider = countProvider
            reviewKeyProvider = keyProvider
            reviewProviderChapterIndex = chapterIndex.takeIf { countProvider != null }
            refreshReviewColumnsLocked()
        }
    }

    fun hasReviewCountProvider(chapterIndex: Int): Boolean {
        return reviewCountProvider != null && reviewProviderChapterIndex == chapterIndex
    }

    fun clearReviewProviders() {
        setReviewProviders(null, null, null)
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

    fun refreshReviewColumns() {
        synchronized(reviewColumnLock) {
            refreshReviewColumnsLocked()
        }
    }

    fun refreshReviewColumnsForStyleChange() {
        refreshReviewColumns()
    }

    private fun refreshReviewColumnsLocked() {
        refreshReviewColumnsLocked(ReadBook.prevTextChapter)
        refreshReviewColumnsLocked(ReadBook.curTextChapter)
        refreshReviewColumnsLocked(ReadBook.nextTextChapter)
    }

    private fun refreshReviewColumnsLocked(textChapter: TextChapter?) {
        textChapter ?: return
        val chapterIndex = textChapter.chapter.index
        textChapter.pages.forEach { page ->
            refreshReviewColumnsLocked(page, chapterIndex)
        }
    }

    internal fun refreshReviewColumns(textPage: TextPage, chapterIndex: Int) {
        synchronized(reviewColumnLock) {
            refreshReviewColumnsLocked(textPage, chapterIndex)
        }
    }

    private fun refreshReviewColumnsLocked(textPage: TextPage, chapterIndex: Int) {
        val titleHasReview = getReviewCount(
            paragraphNum = 0,
            isTitle = true,
            chapterIndex = chapterIndex,
        ) > 0
        textPage.lines.forEach { line ->
            val count = getReviewCount(
                paragraphNum = line.paragraphNum,
                isTitle = line.isReviewTitle,
                titleOffset = line.reviewTitleOffset,
                chapterIndex = chapterIndex,
            )
            val shouldShow = count > 0 && line.isParagraphEnd
            var changed = updateReviewTrailingInset(line, titleHasReview)
            if (!shouldShow) {
                changed = line.removeColumns { it is ReviewColumn } || changed
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

    private fun updateReviewTrailingInset(textLine: TextLine, applied: Boolean): Boolean {
        val trailingPadding = textLine.reviewTrailingPadding ?: return false
        val nextInset = ReviewColumnGeometry.trailingInset(
            getReviewWidth(true),
            trailingPadding,
            1.dpToPx().toFloat(),
        )
        val delta = ReviewColumnGeometry.trailingShift(
            textLine.reviewTrailingInset,
            textLine.isReviewTrailingInsetApplied,
            nextInset,
            applied,
        )
        textLine.reviewTrailingInset = nextInset
        textLine.isReviewTrailingInsetApplied = applied
        if (delta == 0f) return false
        textLine.columns.filterNot { it is ReviewColumn }.forEach { column ->
            column.start += delta
            column.end += delta
        }
        textLine.startX += delta
        return true
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
            isTitle = textLine.isReviewTitle,
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
        val width = getReviewWidth(textLine.isReviewTitle)
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
        val defaultWidth = getReviewHeight(isTitle) * 0.9f
        val aspectRatio = getReviewIconAspectRatio()
            ?.takeIf(::isReviewIconAspectRatioSupported)
            ?: 1f
        val width = defaultWidth * aspectRatio
        val maxWidth = visibleWidth * reviewIconMaxPageWidthRatio
        return if (maxWidth > 0f) minOf(width, maxWidth) else width
    }

    fun isReviewIconAspectRatioSupported(aspectRatio: Float): Boolean {
        return aspectRatio > 0f && aspectRatio <= reviewIconMaxAspectRatio
    }

    fun getReviewHeight(isTitle: Boolean): Float {
        val textSize = if (isTitle) titlePaint.textSize else contentPaint.textSize
        return textSize * ReadBookConfig.reviewIconScale.coerceIn(50, 200) / 100f
    }

    fun getReviewCountText(count: Int): String {
        return if (count > 999) "999" else count.toString()
    }

    fun clearReviewIconCache() = synchronized(reviewIconLock) {
        lastReviewIconTemplate = null
        invalidReviewIconTemplate = null
        lastReviewIconAspectTemplate = null
        reviewIconAspectRatio = null
        reviewIconBitmapCache.evictAll()
    }

    private fun getReviewIconAspectRatio(): Float? = synchronized(reviewIconLock) {
        val template = ReadBookConfig.reviewIconSvg.trim()
        if (template.isBlank()) {
            lastReviewIconAspectTemplate = null
            reviewIconAspectRatio = null
            return@synchronized null
        }
        if (lastReviewIconAspectTemplate != template) {
            lastReviewIconAspectTemplate = template
            reviewIconAspectRatio = SvgUtils.getAspectRatioFromSvgText(
                template.replace(reviewIconPlaceholder, "88")
            )
        }
        reviewIconAspectRatio
    }

    fun getReviewIconBitmap(count: Int, widthPx: Int, heightPx: Int): Bitmap? =
        synchronized(reviewIconLock) {
            if (count <= 0 || widthPx <= 0 || heightPx <= 0) return@synchronized null
            val template = ReadBookConfig.reviewIconSvg.trim()
            if (template.isBlank()) {
                if (lastReviewIconTemplate != null) clearReviewIconCache()
                return@synchronized null
            }
            if (lastReviewIconTemplate != template) {
                reviewIconBitmapCache.evictAll()
                invalidReviewIconTemplate = null
                lastReviewIconTemplate = template
            }
            val aspectRatio = getReviewIconAspectRatio()
            if (aspectRatio == null || !isReviewIconAspectRatioSupported(aspectRatio)) {
                invalidReviewIconTemplate = template
                return@synchronized null
            }
            if (invalidReviewIconTemplate == template) return@synchronized null
            val countText = getReviewCountText(count)
            val cacheKey = ReviewIconCacheKey(countText, widthPx, heightPx)
            reviewIconBitmapCache.get(cacheKey)?.let { return@synchronized it }
            val bitmap = SvgUtils.createBitmapFromSvgText(
                template.replace(reviewIconPlaceholder, countText),
                widthPx,
                heightPx,
            )
            if (bitmap == null) {
                invalidReviewIconTemplate = template
                return@synchronized null
            }
            reviewIconBitmapCache.put(cacheKey, bitmap)
            bitmap
        }

    private fun getTypeface(
        fontPath: String,
        fallback: Typeface? = null,
        onInvalid: () -> Unit = { ReadBookConfig.textFont = "" },
    ): Typeface? {
        val fallbackTypeface = fallback ?: when (AppConfig.systemTypefaces) {
            1 -> Typeface.SERIF
            2 -> Typeface.MONOSPACE
            else -> Typeface.SANS_SERIF
        }
        return kotlin.runCatching {
            when {
                fontPath.isNotEmpty() -> loadTypeface(fontPath)
                else -> fallbackTypeface
            }
        }.getOrElse {
            onInvalid()
            ReadBookConfig.save()
            fallbackTypeface
        } ?: fallbackTypeface
    }

    private fun loadTypeface(fontPath: String): Typeface? = when {
        fontPath.isContentScheme() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
            appCtx.contentResolver.openFileDescriptor(fontPath.toUri(), "r")!!.use {
                Typeface.Builder(it.fileDescriptor).build()
            }
        }

        fontPath.isContentScheme() -> {
            Typeface.createFromFile(RealPathUtil.getPath(appCtx, fontPath.toUri()))
        }

        else -> Typeface.createFromFile(fontPath)
    }

    private data class TypefaceResult(val typeface: Typeface?)

    private val highlightTypefaceCache = object : LruCache<String, TypefaceResult>(8) {
        override fun create(key: String): TypefaceResult {
            return TypefaceResult(kotlin.runCatching { loadTypeface(key) }.getOrNull())
        }
    }

    internal fun getHighlightTypeface(fontPath: String): Typeface? {
        if (fontPath.isEmpty()) return null
        return highlightTypefaceCache[fontPath]?.typeface
    }

    internal fun invalidateHighlightTypeface(fontPath: String) {
        if (fontPath.isNotEmpty()) highlightTypefaceCache.remove(fontPath)
    }

    private fun getPaints(
        titleTypeface: Typeface?,
        textTypeface: Typeface?,
    ): Pair<TextPaint, TextPaint> {
        val titleBold = Typeface.create(titleTypeface, Typeface.BOLD)
        val titleNormal = Typeface.create(titleTypeface, Typeface.NORMAL)
        val textBold = Typeface.create(textTypeface, Typeface.BOLD)
        val textNormal = Typeface.create(textTypeface, Typeface.NORMAL)
        val (titleFont, textFont) = when (ReadBookConfig.textBold) {
            1 -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    Pair(Typeface.create(titleTypeface, 900, false), textBold)
                else
                    Pair(titleBold, textBold)
            }

            2 -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    Pair(titleNormal, Typeface.create(textTypeface, 300, false))
                else
                    Pair(titleNormal, textNormal)
            }

            else -> Pair(titleBold, textNormal)
        }

        //标题
        val tPaint = TextPaint()
        tPaint.color = ReadBookConfig.titleTextColor
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
