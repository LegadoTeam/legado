package io.legado.app.ui.book.read.page.provider

import android.graphics.Paint
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.URLSpan
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.PageAnim
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookContent
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getBookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.model.jsSource.JsSourceReview
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fastSum
import io.legado.app.utils.getTextWidthsCompat
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.LinkedList
import kotlin.math.roundToInt
import android.util.Size
import androidx.core.text.HtmlCompat
import io.legado.app.constant.AppPattern.noWordCountRegex
import io.legado.app.data.appDb
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.atLeastApi28
import io.legado.app.ui.book.read.page.entities.column.TextHtmlColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider.reviewStr
import io.legado.app.ui.book.read.page.provider.ChapterProvider.srcReplaceStr
import io.legado.app.ui.book.read.page.provider.ChapterProvider.srcReplaceChar
import io.legado.app.ui.book.read.page.provider.ChapterProvider.srcReplacementChar
import io.legado.app.utils.StringUtils
import androidx.core.text.parseAsHtml
import androidx.core.util.component1
import androidx.core.util.component2
import io.legado.app.help.TextViewTagHandler
import io.legado.app.help.TextViewTagHandler.Companion.HR_PLACE_CHAR
import io.legado.app.help.TextViewTagHandler.Companion.HR_PLACE_STR
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.paramPattern
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider.reviewChar
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

class TextChapterLayout(
    scope: CoroutineScope,
    private val textChapter: TextChapter,
    private val textPages: MutableList<TextPage>,
    private val book: Book,
    private val bookContent: BookContent,
    private val saveChapterData: Boolean,
) {

    @Volatile
    private var listener: LayoutProgressListener? = textChapter

    private val paddingLeft = ChapterProvider.paddingLeft
    private val paddingRight = ChapterProvider.paddingRight
    private val paddingTop = ChapterProvider.paddingTop

    private val titlePaint = ChapterProvider.titlePaint
    private val titlePaintTextHeight = ChapterProvider.titlePaintTextHeight
    private val titlePaintFontMetrics = ChapterProvider.titlePaintFontMetrics
    private val titleNumberPaint = ChapterProvider.titleNumberPaint
    private val titleNumberPaintTextHeight = ChapterProvider.titleNumberPaintTextHeight
    private val titleNumberPaintFontMetrics = ChapterProvider.titleNumberPaintFontMetrics

    private val contentPaint = ChapterProvider.contentPaint
    private val reviewCharWidth by lazy { contentPaint.measureText(srcReplaceStr) * 1.5556f }
    private val contentPaintTextHeight = ChapterProvider.contentPaintTextHeight
    private val contentPaintFontMetrics = ChapterProvider.contentPaintFontMetrics

    private val titleTopSpacing = ChapterProvider.titleTopSpacing
    private val titleBottomSpacing = ChapterProvider.titleBottomSpacing
    private val titleNumberSpacing = ReadBookConfig.titleNumberSpacing.dpToPx()
    private val lineSpacingExtra = ChapterProvider.lineSpacingExtra
    private val titleLineSpacingExtra = ChapterProvider.titleLineSpacingExtra
    private val paragraphSpacing = ChapterProvider.paragraphSpacing

    private val visibleHeight = ChapterProvider.visibleHeight
    private val visibleWidth = ChapterProvider.visibleWidth

    private val viewWidth = ChapterProvider.viewWidth
    private val doublePage = ChapterProvider.doublePage
    private val indentCharWidth = ChapterProvider.indentCharWidth
    private val stringBuilder = StringBuilder()

    private val bookChapter inline get() = textChapter.chapter
    private val displayTitle inline get() = textChapter.title
    private val chaptersSize inline get() = textChapter.chaptersSize

    private val paragraphIndent = ReadBookConfig.paragraphIndent
    private val titleMode = ReadBookConfig.titleMode
    private val useZhLayout = ReadBookConfig.useZhLayout
    private val isMiddleTitle = ReadBookConfig.isMiddleTitle
    private val isRightTitle = ReadBookConfig.isRightTitle
    private val textFullJustify = ReadBookConfig.textFullJustify
    private val hangingPunctuation = ReadBookConfig.hangingPunctuation
    private val punctuationCompressMode = ReadBookConfig.punctuationCompress
    private val punctuationCompressor = if (punctuationCompressMode.enabled) {
        PunctuationCompressor(contentPaint)
    } else {
        null
    }
    private val adaptSpecialStyle = AppConfig.adaptSpecialStyle
    private val pageAnim = book.getPageAnim()
    private val rightTitleHasReview = isRightTitle && ChapterProvider.getReviewCount(
        paragraphNum = 0,
        isTitle = true,
        chapterIndex = bookChapter.index,
    ) > 0
    private val rightTitleMayHaveReview = isRightTitle && if (
        ChapterProvider.hasReviewCountProvider(bookChapter.index)
    ) {
        rightTitleHasReview
    } else {
        ReadBook.bookSource?.let { source ->
            if (source.isJsSource()) {
                JsSourceReview.hasReviewCapability(source)
            } else {
                source.ruleReview?.configuredSummaryUrl() != null
            }
        } == true
    }
    private val rightTitleReviewInset = if (isRightTitle) {
        ReviewColumnGeometry.trailingInset(
            ChapterProvider.getReviewWidth(true),
            paddingRight.toFloat(),
            1.dpToPx().toFloat()
        )
    } else {
        0f
    }
    private val splitTitle = ChapterTitleParser.split(
        displayTitle,
        ReadBookConfig.splitChapterTitle,
        bookChapter.isVolume
    )
    private val reviewTitleOffset = if (
        titleMode != 2 || bookChapter.isVolume || !textChapter.hasBodyContent
    ) {
        if (splitTitle == null) 1 else 2
    } else {
        0
    }

    private var pendingTextPage = TextPage()

    private var durY = 0f
    private var absStartX = paddingLeft
    private var floatArray = FloatArray(128)

    private var isCompleted = false
    private val job: Coroutine<*>

    var exception: Throwable? = null

    var channel = Channel<TextPage>(Channel.UNLIMITED)


    init {
        job = Coroutine.async(
            scope,
            start = CoroutineStart.LAZY,
            executeContext = IO
        ) {
            if (saveChapterData) {
                launch {
                    val bookSource = book.getBookSource() ?: return@launch
                    BookHelp.saveImages(bookSource, book, bookChapter, bookContent.toString())
                }
            }
            getTextChapter(book, bookChapter, displayTitle, bookContent)
        }.onError {
            exception = it
            onException(it)
        }.onCancel {
            channel.cancel()
        }.onFinally {
            isCompleted = true
        }
        job.start()
    }

    fun cancel() {
        job.cancel()
        listener = null
    }

    private fun onPageCompleted() {
        val textPage = pendingTextPage
        textPage.index = textPages.size
        textPage.chapterIndex = bookChapter.index
        textPage.chapterSize = chaptersSize
        textPage.title = displayTitle
        textPage.doublePage = doublePage
        textPage.paddingTop = paddingTop
        textPage.isCompleted = true
        textPage.textChapter = textChapter
        textPage.upLinesPosition()
        textPage.upRenderHeight()
        textPages.add(textPage)
        if (ChapterProvider.hasReviewCountProvider(bookChapter.index)) {
            ChapterProvider.refreshReviewColumns(textPage, bookChapter.index)
        }
        channel.trySend(textPage)
        try {
            listener?.onLayoutPageCompleted(textPages.lastIndex, textPage)
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.put("调用布局进度监听回调出错\n${e.localizedMessage}", e)
        }
    }

    private fun onCompleted() {
        channel.close()
        try {
            listener?.onLayoutCompleted()
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.put("调用布局进度监听回调出错\n${e.localizedMessage}", e)
        } finally {
            listener = null
        }
    }

    private fun onException(e: Throwable) {
        channel.close(e)
        if (e is CancellationException) {
            listener = null
            return
        }
        try {
            listener?.onLayoutException(e)
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.put("调用布局进度监听回调出错\n${e.localizedMessage}", e)
        } finally {
            listener = null
        }
    }

    /**
     * 获取拆分完的章节数据
     */
    private suspend fun getTextChapter(
        book: Book,
        bookChapter: BookChapter,
        displayTitle: String,
        bookContent: BookContent,
    ) {
        val contents = bookContent.textList
        val imageStyle = book.getImageStyle()
        val isSingleImageStyle = imageStyle.equals(Book.imgStyleSingle, true)

        if (titleMode != 2 || bookChapter.isVolume || contents.isEmpty()) {
            val titleLines = splitTitle?.let {
                listOf(it.first to true, it.second to false)
            } ?: displayTitle.splitNotBlank("\n").map { it to false }
            val titleImageIndex = titleLines.indexOfFirst { !it.second }.coerceAtLeast(0)
            val titleImg = bookChapter.imgUrl?.takeIf { it.isNotBlank() }
            var titleImgClick: String? = null
            var parsedTitleImgText: Char? = null
            if (titleImg != null) {
                val urlMatcher = paramPattern.matcher(titleImg)
                var style: String? = null
                var imgSize = ImageProvider.getImageSize(book, titleImg, ReadBook.bookSource)
                if (urlMatcher.find()) {
                    var width: String? = null
                    val urlOptionStr = titleImg.substring(urlMatcher.end())
                    GSON.fromJsonObject<Map<String, String>>(urlOptionStr).getOrNull()
                        ?.let { map ->
                            map.forEach { (key, value) ->
                                when (key) {
                                    "style" -> style = value
                                    "width" -> width = value
                                    "click" -> titleImgClick = value
                                }
                            }
                        }
                    width?.let { widthValue ->
                        if (widthValue.endsWith("%")) {
                            widthValue.dropLast(1).toIntOrNull()?.let { percentage ->
                                val imgWidth = visibleWidth * percentage / 100
                                val (sizeHeight, sizeWidth) = imgSize
                                imgSize = Size(imgWidth, sizeHeight * imgWidth / sizeWidth)
                            }
                        } else {
                            widthValue.toIntOrNull()?.let { width ->
                                val (sizeHeight, sizeWidth) = imgSize
                                imgSize = Size(width, sizeHeight * width / sizeWidth)
                            }
                        }
                    }
                }
                if (style == null) {
                    style = if (imgSize.width < 80 && imgSize.height < 80) {
                        "text"
                    } else {
                        imageStyle
                    }
                }
                when (style) {
                    "text" -> parsedTitleImgText = srcReplaceChar
                    "TEXT" -> parsedTitleImgText = reviewChar
                    else -> setTypeImage(
                        book,
                        titleImg,
                        contentPaintTextHeight,
                        style,
                        imgSize,
                        titleImgClick,
                        isTitle = true
                    )
                }
            }
            val titleImgText = parsedTitleImgText
            //标题非隐藏
            titleLines.forEachIndexed { lineIndex, (text, isTitleNumber) ->
                val srcList = LinkedList<String>()
                val clickList = LinkedList<String?>()
                if (lineIndex == titleImageIndex && titleImg != null && titleImgText != null) {
                    srcList.add(titleImg)
                    clickList.add(titleImgClick)
                }
                setTypeText(
                    book,
                    if (lineIndex == titleImageIndex && titleImgText != null) {
                        text + titleImgText
                    } else {
                        text
                    },
                    if (isTitleNumber) titleNumberPaint else titlePaint,
                    if (isTitleNumber) titleNumberPaintTextHeight else titlePaintTextHeight,
                    if (isTitleNumber) titleNumberPaintFontMetrics else titlePaintFontMetrics,
                    imageStyle,
                    srcList = srcList,
                    clickList = clickList,
                    isTitle = true,
                    isTitleNumber = isTitleNumber,
                    emptyContent = contents.isEmpty(),
                    isVolumeTitle = bookChapter.isVolume
                )
                pendingTextPage.lines.lastOrNull()?.let { titleLine ->
                    titleLine.isParagraphEnd = true
                    if (rightTitleHasReview) {
                        ChapterProvider.appendReviewColumnIfNeeded(
                            titleLine,
                            chapterIndex = bookChapter.index,
                        )
                    }
                }
                if (isTitleNumber) durY += titleNumberSpacing
                stringBuilder.append("\n")
            }
            durY += titleBottomSpacing

            // 如果是单图模式且当前页有内容，强制分页
            if (isSingleImageStyle && pendingTextPage.lines.isNotEmpty() && contents.isNotEmpty()) {
                prepareNextPageIfNeed()
            }
        }
        textChapter.layoutTitleLength =
            textPages.sumOf { it.text.length } + stringBuilder.length

        val isTextImageStyle = imageStyle.equals(Book.imgStyleText, true)

        val sb = StringBuffer()
        var isSetTypedImage = false
        var wordCount = 0
        contents.forEach { content ->
            currentCoroutineContext().ensureActive()
            if (adaptSpecialStyle) {
                val text = content.trim()
                if (text == "[newpage]") {
                    prepareNextPageIfNeed()
                    return@forEach
                } else if (text.startsWith("<usehtml>")) {
                    val endInt = text.lastIndexOf("<")
                    if (endInt > 9) {
                        setTypeHtml(imageStyle, book, text.substring(9, endInt))
                        return@forEach
                    }
                }
            }
            var text = content.replace(srcReplaceChar, srcReplacementChar)
            if (isTextImageStyle) {
                //图片样式为文字嵌入类型
                val srcList = LinkedList<String>()
                sb.setLength(0)
                val matcher = AppPattern.imgPattern.matcher(text)
                while (matcher.find()) {
                    matcher.group(1)?.let { src ->
                        srcList.add(src)
                        matcher.appendReplacement(sb, srcReplaceStr)
                    }
                }
                matcher.appendTail(sb)
                text = sb.toString()
                wordCount += text.replace(noWordCountRegex,"").length
                setTypeText(
                    book,
                    text,
                    contentPaint,
                    contentPaintTextHeight,
                    contentPaintFontMetrics,
                    imageStyle,
                    srcList = srcList,
                    clickList = null
                )
            } else {
                if (isSingleImageStyle && isSetTypedImage) {
                    isSetTypedImage = false
                    prepareNextPageIfNeed()
                }
                var start = 0
                val srcList = LinkedList<String>()
                val clickList = LinkedList<String?>()
                sb.setLength(0)
                var isFirstLine = true
                if (content.contains("<img")) {
                    val matcher = AppPattern.imgPattern.matcher(text)
                    while (matcher.find()) {
                        currentCoroutineContext().ensureActive()
                        val imgSrc = matcher.group(1)!!
                        var style: String? = null
                        var click: String? = null
                        var imgSize = ImageProvider.getImageSize(book, imgSrc, ReadBook.bookSource)
                        val urlMatcher = paramPattern.matcher(imgSrc)
                        if (urlMatcher.find()) {
                            var width: String? = null
                            val urlOptionStr = imgSrc.substring(urlMatcher.end())
                            GSON.fromJsonObject<Map<String, String>>(urlOptionStr).getOrNull()?.let { map ->
                                map.forEach { (key, value) ->
                                    when (key) {
                                        "style" -> style = value
                                        "width" -> width = value
                                        "click" -> click = value
                                    }
                                }
                            }
                            width?.let {
                                if (width.endsWith("%")) {
                                    width.dropLast(1).toIntOrNull()?.let { percentage ->
                                        val imgWidth = visibleWidth * percentage / 100
                                        val (sizeHeight, sizeWidth) = imgSize
                                        imgSize = Size(imgWidth, sizeHeight * imgWidth / sizeWidth)
                                    }
                                } else {
                                    width.toIntOrNull()?.let { width ->
                                        val (sizeHeight, sizeWidth) = imgSize
                                        imgSize = Size(width, sizeHeight * width / sizeWidth)
                                    }
                                }
                            }
                        }
                        if (style == null) {
                            style = if (imgSize.width < 80 && imgSize.height < 80) {
                                "text"
                            } else {
                                imageStyle
                            }
                        }
                        if (start < matcher.start()) {
                            sb.append(text.subSequence(start, matcher.start()))
                        }
                        when (style) {
                            "TEXT" -> {
                                sb.append(reviewChar)
                                srcList.add(imgSrc)
                                clickList.add(click)
                            }
                            "text" -> {
                                sb.append(srcReplaceChar)
                                srcList.add(imgSrc)
                                clickList.add(click)
                            }
                            else -> {
                                val textBefore = sb.toString()
                                if (textBefore.isNotBlank()) {
                                    wordCount += textBefore.replace(noWordCountRegex, "").length
                                    setTypeText(
                                        book,
                                        sb.toString(),
                                        contentPaint,
                                        contentPaintTextHeight,
                                        contentPaintFontMetrics,
                                        "TEXT",
                                        isFirstLine = isFirstLine,
                                        srcList = srcList,
                                        clickList = clickList
                                    )
                                    sb.setLength(0)
                                    isFirstLine = false
                                }
                                setTypeImage(
                                    book,
                                    imgSrc,
                                    contentPaintTextHeight,
                                    style,
                                    imgSize,
                                    click
                                )
                                isSetTypedImage = true
                            }
                        }
                        start = matcher.end()
                    }
                }
                if (start < content.length) {
                    if (isSingleImageStyle && isSetTypedImage) {
                        isSetTypedImage = false
                        prepareNextPageIfNeed()
                    }
                    val textAfter = content.subSequence(start, content.length)
                    sb.append(textAfter)
                }
                text = sb.toString()
                if (text.isNotBlank()) {
                    wordCount += text.replace(noWordCountRegex,"").length
                    setTypeText(
                        book,
                        text,
                        contentPaint,
                        contentPaintTextHeight,
                        contentPaintFontMetrics,
                        "TEXT",
                        isFirstLine = isFirstLine,
                        srcList = srcList,
                        clickList = clickList
                    )
                }
            }
            pendingTextPage.lines.lastOrNull()?.let { line ->
                line.isParagraphEnd = true
                ChapterProvider.appendReviewColumnIfNeeded(
                    line,
                    chapterIndex = bookChapter.index,
                )
            }
            stringBuilder.append("\n")
        }
        if (saveChapterData) {
            val chapterWordCount = StringUtils.wordCountFormat(wordCount.toString())
            bookChapter.wordCount = chapterWordCount
            appDb.bookChapterDao.upWordCount(bookChapter.bookUrl, bookChapter.url, chapterWordCount)
        }
        val textPage = pendingTextPage
        val endPadding = 20.dpToPx()
        val durYPadding = durY + endPadding
        if (textPage.height < durYPadding) {
            textPage.height = durYPadding
        } else {
            textPage.height += endPadding
        }
        textPage.text = stringBuilder.toString()
        currentCoroutineContext().ensureActive()
        onPageCompleted()
        onCompleted()
    }

    /**
     * 排版图片
     */
    private suspend fun setTypeImage(
        book: Book,
        src: String,
        textHeight: Float,
        imageStyle: String?,
        size: Size,
        click: String?,
        isTitle: Boolean = false
    ) {
        if (size.width > 0 && size.height > 0) {
            prepareNextPageIfNeed(durY)
            var height = size.height
            var width = size.width
            when (imageStyle?.uppercase()) {
                Book.imgStyleFull -> {
                    width = visibleWidth
                    height = size.height * visibleWidth / size.width
                    if (pageAnim != PageAnim.scrollPageAnim && height > visibleHeight - durY) {
                        if (height > visibleHeight) {
                            width = width * visibleHeight / height
                            height = visibleHeight
                        }
                        prepareNextPageIfNeed(durY + height)
                    }
                }

                Book.imgStyleSingle -> {
                    width = visibleWidth
                    height = size.height * visibleWidth / size.width
                    if (height > visibleHeight) {
                        width = width * visibleHeight / height
                        height = visibleHeight
                    }
                    if (durY > 0f) {
                        prepareNextPageIfNeed()
                    }

                    // 图片竖直方向居中：调整 Y 坐标
                    if (height < visibleHeight) {
                        val adjustHeight = (visibleHeight - height) / 2f
                        durY = adjustHeight // 将 Y 坐标设置为居中位置
                    }
                }

                else -> {
                    if (size.width > visibleWidth) {
                        height = size.height * visibleWidth / size.width
                        width = visibleWidth
                    }
                    if (height > visibleHeight) {
                        width = width * visibleHeight / height
                        height = visibleHeight
                    }
                    prepareNextPageIfNeed(durY + height)
                }
            }
            val textLine = TextLine(
                isTitle = isTitle,
                isImage = true,
                reviewTitleOffset = reviewTitleOffset,
            )
            if (doublePage) {
                textLine.isLeftLine = absStartX < viewWidth / 2
            }
            textLine.text = " "
            textLine.lineTop = durY + paddingTop
            durY += height
            textLine.lineBottom = durY + paddingTop
            textLine.lineBase = textLine.lineBottom
            val (start, end) = if (visibleWidth > width) {
                when (imageStyle?.uppercase()) {
                    "RIGHT" -> Pair(visibleWidth - width, visibleWidth)
                    "LEFT" -> Pair(0f, width)
                    else -> {
                        val adjustWidth = (visibleWidth - width) / 2f
                        Pair(adjustWidth, adjustWidth + width)
                    }
                }
            } else {
                Pair(0f, width)
            }
            textLine.addColumn(
                ImageColumn(start = absStartX + start.toFloat(), end = absStartX + end.toFloat(), src = src, click = click)
            )
            calcTextLinePosition(textPages, textLine, stringBuilder.length)
            stringBuilder.append(" ") // 确保翻页时索引计算正确
            pendingTextPage.addLine(textLine)
        }
        durY += textHeight * paragraphSpacing / 10f
    }

    /**
     * 排版html样式
     */
    private suspend fun setTypeHtml(
        imageStyle: String?,
        book: Book,
        htmlContent: String,
    ) {
        val textViewTagHandler = TextViewTagHandler()
        val spanned = htmlContent.parseAsHtml(HtmlCompat.FROM_HTML_MODE_COMPACT, tagHandler = textViewTagHandler)
        val width = visibleWidth
        val textPaint = contentPaint
        val textColor = ReadBookConfig.textColor
        if (textPaint.color != textColor) {
            textPaint.color = textColor
        }
        val staticLayout = if (atLeastApi28) {
            StaticLayout.Builder.obtain(spanned, 0, spanned.length, textPaint, width)
                .setIncludePad(true)
                .setUseLineSpacingFromFallbacks(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(
                spanned,
                textPaint,
                width,
                Layout.Alignment.ALIGN_NORMAL,
                1f,
                0f,
                true
            )
        }
        val tempPaint = TextPaint(textPaint)
        for (lineIndex in 0 until staticLayout.lineCount) {
            val lineStart = staticLayout.getLineStart(lineIndex)
            val lineEnd = staticLayout.getLineEnd(lineIndex)
            if (lineStart == lineEnd) { //这一行没有内容，跳过
                continue
            }
            val textLine = TextLine(
                isHtml = true,
                reviewTitleOffset = reviewTitleOffset,
            )
            val lineText = StringBuilder()
            val lineLeft = staticLayout.getLineLeft(lineIndex)
            textLine.startX = absStartX + lineLeft //x坐标
            val mLineTop = staticLayout.getLineTop(lineIndex).toFloat()
            val mLineBottom = staticLayout.getLineBottom(lineIndex).toFloat()
            val lineHeight = mLineBottom - mLineTop
            prepareNextPageIfNeed(durY + lineHeight)
            if (doublePage) {
                textLine.isLeftLine = absStartX < viewWidth / 2
            }
            textLine.upTopBottom(durY, lineHeight, textPaint.fontMetrics) //y坐标

            val columns = mutableListOf<BaseColumn>()
            var charIndex = lineStart
            while (charIndex < lineEnd) {
                val char = spanned[charIndex].toString()
                lineText.append(char)
                if (char == "\n") {
                    textLine.isParagraphEnd = true
                    durY += lineHeight * paragraphSpacing / 10f //段距
                    charIndex++
                    continue
                }
                val charX = staticLayout.getPrimaryHorizontal(charIndex)
                val textSize = extractTextSize(spanned, charIndex, textPaint.textSize)
                val textColor = extractTextColor(spanned, charIndex)
                val linkUrl = extractLinkUrl(spanned, charIndex)
                val charRight = if (charIndex + 1 < lineEnd) {
                    staticLayout.getPrimaryHorizontal(charIndex + 1)
                } else {
                    tempPaint.textSize = textSize
                    val charWidth = tempPaint.measureText(char)
                    charX + charWidth
                }
                var needAddText = true
                spanned.getSpans(charIndex, charIndex + 1, ImageSpan::class.java).firstOrNull()?.let { span -> //处理图片
                    val source = span.source ?: return@let
                    val urlMatcher = paramPattern.matcher(source)
                    if (urlMatcher.find()) {
                        val urlOptionStr = source.substring(urlMatcher.end())
                        val urlOption = GSON.fromJsonObject<Map<String, String>>(urlOptionStr).getOrNull() ?: return@let
                        var iStyle = urlOption["style"]
                        val width = urlOption["width"]
                        val click = urlOption["click"]
                        var imgSize = ImageProvider.getImageSize(book, source, ReadBook.bookSource)
                        width?.let {
                            if (width.endsWith("%")) {
                                width.dropLast(1).toIntOrNull()?.let { percentage ->
                                    val imgWidth = visibleWidth * percentage / 100
                                    val (sizeHeight, sizeWidth) = imgSize
                                    imgSize = Size(imgWidth, sizeHeight * imgWidth / sizeWidth)
                                }
                            } else {
                                width.toIntOrNull()?.let { width ->
                                    val (sizeHeight, sizeWidth) = imgSize
                                    imgSize = Size(width, sizeHeight * width / sizeWidth)
                                }
                            }
                        }
                        if (iStyle == null) {
                            iStyle = if (imgSize.width < 80 && imgSize.height < 80) {
                                "text"
                            } else {
                                imageStyle
                            }
                        }
                        when (iStyle?.uppercase()) {
                            "TEXT" -> {
                                ImageProvider.cacheImage(book, source, ReadBook.bookSource)
                                columns.add(
                                    ImageColumn(
                                        start = absStartX + charX,
                                        end = absStartX + charRight,
                                        src = source,
                                        click = click
                                    )
                                )
                            }
                            else -> {
                                setTypeImage(
                                    book,
                                    source,
                                    contentPaintTextHeight,
                                    iStyle,
                                    imgSize,
                                    click
                                )
                            }
                        }
                    } else {
                        val imgSize = ImageProvider.getImageSize(book, source, ReadBook.bookSource)
                        setTypeImage(
                            book,
                            source,
                            contentPaintTextHeight,
                            imageStyle,
                            imgSize,
                            null
                        )
                    }
                    needAddText = false
                }
                spanned.getSpans(charIndex, charIndex + 1, ReplacementSpan::class.java).firstOrNull()?.let { _ -> //自定义标签
                    if (char == HR_PLACE_CHAR) {
                        columns.add(
                            TextHtmlColumn(
                                absStartX.toFloat(),
                                (absStartX + width - paddingRight).toFloat(),
                                HR_PLACE_STR,
                                textSize,
                                textColor,
                                linkUrl
                            )
                        )
                        needAddText = false
                    }
                }
                if (needAddText) {
                    columns.add(
                        TextHtmlColumn(
                            absStartX + charX,
                            absStartX + charRight,
                            char,
                            textSize,
                            textColor,
                            linkUrl
                        )
                    )
                }
                charIndex++
                if (charIndex == lineEnd && lineIndex == staticLayout.lineCount - 1) {
                    textLine.isParagraphEnd = true
                    durY += lineHeight * paragraphSpacing / 10f //段距
                }
            }
            textLine.text = lineText.toString()
            if (textFullJustify && !textLine.isParagraphEnd) {
                justifyHtmlLine(columns, textLine, visibleWidth)
            } else {
                textLine.addColumns(columns)
            }
            calcTextLinePosition(textPages, textLine, stringBuilder.length)
            stringBuilder.append(lineText)
            val textPage = pendingTextPage
            textPage.addLine(textLine)
            durY += lineHeight * lineSpacingExtra //行距
            if (textPage.height < durY) {
                textPage.height = durY
            }
        }
    }

    /**
     * 对HTML行进行两端对齐
     */
    private fun justifyHtmlLine(
        columns: MutableList<BaseColumn>,
        textLine: TextLine,
        lineWidth: Int
    ) {
        if (columns.isEmpty()) return
        // 计算当前行的总宽度
        val firstCol = columns.first()
        val lastCol = columns.last()
        val currentWidth = lastCol.end - firstCol.start
        // 计算剩余空间
        val residualWidth = lineWidth - currentWidth

        if (residualWidth <= 0) {
            textLine.addColumns(columns)
            return
        }

        // 统计空格数量
        val spaceCount = columns.count {
            (it as? TextBaseColumn)?.charData == " "
        }

        if (spaceCount > 1) {
            // 多个空格：调整空格间距
            val spaceIncrement = residualWidth / spaceCount
            textLine.wordSpacing = spaceIncrement

            // 重新计算字符位置
            var currentX = firstCol.start
            for (i in columns.indices) {
                val col = columns[i]
                val width = col.end - col.start

                if ((col as? TextBaseColumn)?.charData == " " && i != columns.lastIndex) {
                    // 空格，增加额外的间距
                    col.start = currentX
                    col.end = currentX + width + spaceIncrement
                    currentX = col.end
                } else {
                    // 非空格或最后一个字符
                    col.start = currentX
                    col.end = currentX + width
                    currentX = col.end
                }

                textLine.addColumn(col)
            }
        } else {
            // 没有或只有一个空格：调整字符间距
            val gapCount = columns.lastIndex
            if (gapCount > 0) {
                val charIncrement = residualWidth / gapCount
                var currentX = firstCol.start
                for (i in columns.indices) {
                    val col = columns[i]
                    val width = col.end - col.start

                    if (i != columns.lastIndex) {
                        // 非最后一个字符，增加额外的间距
                        col.start = currentX
                        col.end = currentX + width + charIncrement
                        currentX = col.end
                    } else {
                        // 最后一个字符，不增加额外间距
                        col.start = currentX
                        col.end = currentX + width
                    }

                    textLine.addColumn(col)
                }
            } else {
                // 只有一个字符，不需要调整
                textLine.addColumns(columns)
            }
        }
    }

    private fun extractTextSize(spanned: Spanned, index: Int, defaultSize: Float): Float {
        val relativeSpans = spanned.getSpans(index, index + 1, RelativeSizeSpan::class.java)
        // 如果有 RelativeSizeSpan，基于基准大小计算
        relativeSpans.firstOrNull()?.let { span ->
            return defaultSize * span.sizeChange
        }
//        val sizeSpans = spanned.getSpans(index, index + 1, AbsoluteSizeSpan::class.java)
//        sizeSpans.firstOrNull()?.let { span ->
//            return span.size.toFloat()
//        }
        return defaultSize
    }

    private fun extractTextColor(spanned: Spanned, index: Int): Int? {
        val foregroundSpans = spanned.getSpans(index, index + 1, ForegroundColorSpan::class.java)
        return foregroundSpans.firstOrNull()?.foregroundColor
    }

    private fun extractLinkUrl(spanned: Spanned, index: Int): String? {
        // 检查URLSpan（超链接）
        val urlSpans = spanned.getSpans(index, index + 1, URLSpan::class.java)
        urlSpans.firstOrNull()?.let { span ->
            return span.url
        }
        return null
    }


    /**
     * 排版文字
     */
    @Suppress("DEPRECATION")
    private suspend fun setTypeText(
        book: Book,
        text: String,
        textPaint: TextPaint,
        textHeight: Float,
        fontMetrics: Paint.FontMetrics,
        imageStyle: String?,
        isTitle: Boolean = false,
        isTitleNumber: Boolean = false,
        isFirstLine: Boolean = true,
        emptyContent: Boolean = false,
        isVolumeTitle: Boolean = false,
        srcList: LinkedList<String>? = null,
        clickList: LinkedList<String?>?
    ) {
        val lineSpacing = if (isTitle && !isTitleNumber) {
            titleLineSpacingExtra
        } else {
            lineSpacingExtra
        }
        val widthsArray = allocateFloatArray(text.length)
        textPaint.getTextWidthsCompat(text, widthsArray, reviewCharWidth)
        //标点挤压改写字宽,断行与列排布都用挤压后的宽度,标题不参与
        val compressor = if (isTitle) null else punctuationCompressor
        compressor?.beginParagraph(text, widthsArray, punctuationCompressMode)
        val hangingWidth = hangingPunctuationWidth(text, widthsArray, isTitle, isFirstLine)
        val usesRightTitleReviewInset =
            isTitle && rightTitleMayHaveReview && !emptyContent && !isVolumeTitle &&
            imageStyle?.uppercase() != Book.imgStyleSingle
        // 章评统计异步返回时先稳定换行，出现图标后只平移标题列。
        val textLayoutWidth = if (usesRightTitleReviewInset) {
            (visibleWidth - rightTitleReviewInset).toInt().coerceAtLeast(1)
        } else {
            visibleWidth
        }
        val layout = if (useZhLayout) {
            val (words, widths) = measureTextSplit(text, widthsArray)
            val indentSize = if (isFirstLine) paragraphIndent.length else 0
            ZhLayout(text, textPaint, textLayoutWidth, words, widths, indentSize, hangingWidth)
        } else if (hangingWidth > 0f) {
            //悬挂标点不占行宽,首行放宽后其余行缩回版心
            val layoutWidth = HangingLineWidth.layoutWidth(textLayoutWidth, hangingWidth)
            StaticLayout.Builder
                .obtain(text, 0, text.length, textPaint, layoutWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 0f)
                .setIncludePad(true)
                .setIndents(null, HangingLineWidth.rightIndents(textLayoutWidth, layoutWidth))
                .build()
        } else {
            StaticLayout(text, textPaint, textLayoutWidth, Layout.Alignment.ALIGN_NORMAL, 0f, 0f, true)
        }
        val layoutHeight = if (isTitle && !isTitleNumber) {
            if (layout.lineCount == 0) {
                0f
            } else {
                textHeight * (1f + (layout.lineCount - 1) * lineSpacing)
            }
        } else {
            layout.lineCount * textHeight
        }
        durY = when {
            //标题y轴居中
            emptyContent && textPages.isEmpty() -> {
                val textPage = pendingTextPage
                if (textPage.lineSize == 0) {
                    val ty = (visibleHeight - layoutHeight) / 2
                    if (ty > titleTopSpacing) ty else titleTopSpacing.toFloat()
                } else {
                    var textLayoutHeight = layoutHeight
                    val fistLine = textPage.getLine(0)
                    if (fistLine.lineTop < textLayoutHeight + titleTopSpacing) {
                        textLayoutHeight = fistLine.lineTop - titleTopSpacing
                    }
                    textPage.lines.forEach {
                        it.lineTop -= textLayoutHeight
                        it.lineBase -= textLayoutHeight
                        it.lineBottom -= textLayoutHeight
                    }
                    durY - textLayoutHeight
                }
            }

            isTitle && textPages.isEmpty() && pendingTextPage.lines.isEmpty() -> {
                when (imageStyle?.uppercase()) {
                    Book.imgStyleSingle -> {
                        val ty = (visibleHeight - layoutHeight) / 2
                        if (ty > titleTopSpacing) ty else titleTopSpacing.toFloat()
                    }

                    else -> durY + titleTopSpacing
                }
            }

            else -> durY
        }
        for (lineIndex in 0 until layout.lineCount) {
            val textLine = TextLine(
                isTitle = isTitle,
                isTitleNumber = isTitleNumber,
                reviewTitleOffset = reviewTitleOffset,
                reviewTrailingInset = if (usesRightTitleReviewInset) {
                    rightTitleReviewInset
                } else {
                    0f
                },
                reviewTrailingPadding = paddingRight.toFloat()
                    .takeIf { usesRightTitleReviewInset },
                isReviewTrailingInsetApplied = usesRightTitleReviewInset && rightTitleHasReview,
            )
            prepareNextPageIfNeed(durY + textHeight)
            val lineStart = layout.getLineStart(lineIndex)
            val lineEnd = layout.getLineEnd(lineIndex)
            val lineText = text.substring(lineStart, lineEnd)
            val (words, widths) = measureTextSplit(lineText, widthsArray, lineStart)
            if (
                punctuationCompressMode.compressLineEnd &&
                lineIndex < layout.lineCount - 1
            ) {
                //末行的行尾就是段尾,压了只会让右边界无故缩进
                compressor?.compressLineEnd(words, widths, lineStart)
            }
            //挤压过的列窄于字宽,整行一次性绘制会按原字宽排字,须退回逐列绘制
            val drawOffsets = compressor?.lineDrawOffsets(words, lineStart)
            if (drawOffsets != null) {
                textLine.compressedPunctuation = true
            }
            val desiredWidth = widths.fastSum()
            textLine.text = lineText
            val titleStartX = when {
                !isTitle -> null
                emptyContent || isVolumeTitle ||
                        imageStyle?.uppercase() == Book.imgStyleSingle -> {
                    (visibleWidth - desiredWidth) / 2
                }
                isMiddleTitle -> (visibleWidth - desiredWidth) / 2
                isRightTitle -> {
                    val trailingInset = if (textLine.isReviewTrailingInsetApplied) {
                        textLine.reviewTrailingInset
                    } else {
                        0f
                    }
                    (visibleWidth - desiredWidth - trailingInset).coerceAtLeast(0f)
                }
                else -> null
            }
            when (lineIndex) {
                0 if layout.lineCount > 1 && !isTitle && isFirstLine -> {
                    //多行的第一行 非标题
                    addCharsToLineFirst(
                        book, absStartX, textLine, words, textPaint,
                        desiredWidth, widths, srcList, clickList, hangingWidth, drawOffsets
                    )
                }
                layout.lineCount - 1 -> {
                    //最后一行、单行
                    addCharsToLineNatural(
                        book, absStartX, textLine, words,
                        titleStartX ?: 0f, !isTitle && lineIndex == 0,
                        widths, srcList, clickList,
                        if (lineIndex == 0) hangingWidth else 0f, drawOffsets
                    )
                }
                else -> {
                    if (titleStartX != null) {
                        addCharsToLineNatural(
                            book, absStartX, textLine, words,
                            titleStartX, false, widths, srcList, clickList
                        )
                    } else {
                        //中间行
                        addCharsToLineMiddle(
                            book, absStartX, textLine, words, textPaint,
                            desiredWidth, 0f, widths, srcList, clickList, drawOffsets
                        )
                    }
                }
            }
            if (doublePage) {
                textLine.isLeftLine = absStartX < viewWidth / 2
            }
            calcTextLinePosition(textPages, textLine, stringBuilder.length)
            stringBuilder.append(lineText)
            textLine.upTopBottom(durY, textHeight, fontMetrics)
            val textPage = pendingTextPage
            textPage.addLine(textLine)
            durY += textHeight * lineSpacing
            if (textPage.height < durY) {
                textPage.height = durY
            }
        }
        durY += textHeight * paragraphSpacing / 10f
    }

    private fun calcTextLinePosition(
        textPages: MutableList<TextPage>,
        textLine: TextLine,
        sbLength: Int
    ) {
        val lastLine = pendingTextPage.lines.lastOrNull { it.paragraphNum > 0 }
            ?: textPages.lastOrNull()?.lines?.lastOrNull { it.paragraphNum > 0 }
        val paragraphNum = when {
            lastLine == null -> 1
            lastLine.isParagraphEnd -> lastLine.paragraphNum + 1
            else -> lastLine.paragraphNum
        }
        textLine.paragraphNum = paragraphNum
        textLine.chapterPosition =
            (textPages.lastOrNull()?.lines?.lastOrNull()?.run {
                chapterPosition + charSize + if (isParagraphEnd) 1 else 0
            } ?: 0) + sbLength
        textLine.pagePosition = sbLength
    }

    /**
     * 计算段首标点悬挂宽度,不悬挂时返回0
     */
    private fun hangingPunctuationWidth(
        text: String,
        widthsArray: FloatArray,
        isTitle: Boolean,
        isFirstLine: Boolean
    ): Float {
        if (!hangingPunctuation || isTitle || !isFirstLine) return 0f
        if (!HangingPunctuationRule.shouldHang(text, paragraphIndent)) return 0f
        return LineColumnLayout.hangingWidth(widthsArray, paragraphIndent.length, indentCharWidth)
    }

    /**
     * 有缩进,两端对齐
     */
    private suspend fun addCharsToLineFirst(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        words: List<String>,
        textPaint: TextPaint,
        /**自然排版长度**/
        desiredWidth: Float,
        textWidths: List<Float>,
        srcList: LinkedList<String>?,
        clickList: LinkedList<String?>?,
        hangingWidth: Float,
        /**挤压过的标点在列内的绘制偏移,与 words 同下标*/
        drawOffsets: FloatArray?
    ) {
        if (!textFullJustify) {
            addCharsToLineNatural(
                book, absStartX, textLine, words,
                0f, true, textWidths, srcList, clickList, hangingWidth, drawOffsets
            )
            return
        }
        textLine.indentSize = paragraphIndent.length
        val wordStart = LineColumnLayout.justifiedFirst(
            words, textWidths, visibleWidth.toFloat(), desiredWidth,
            paragraphIndent.length, indentCharWidth, hangingWidth,
            onIndentWidth = { textLine.indentWidth = it },
            onJustify = { startX, gap, isWordSpacing ->
                applyJustify(textLine, textPaint, absStartX, startX, gap, isWordSpacing)
            }
        ) { index, xStart, xEnd, kind ->
            if (kind == LineColumnLayout.kindIndent) {
                textLine.addColumn(
                    TextColumn(
                        charData = ChapterProvider.indentChar,
                        start = absStartX + xStart,
                        end = absStartX + xEnd
                    )
                )
            } else {
                if (kind == LineColumnLayout.kindHanging) {
                    textLine.hangingPunctuation = true
                }
                addCharToLine(
                    book, absStartX, textLine, words[index], drawOffsets?.get(index) ?: 0f,
                    xStart, xEnd, index + 1 == words.size, srcList, clickList
                )
            }
        }
        if (words.size > wordStart) {
            exceed(absStartX, textLine, words.subList(wordStart, words.size))
        }
    }

    /**
     * 两端对齐的行内间距
     */
    private fun applyJustify(
        textLine: TextLine,
        textPaint: TextPaint,
        absStartX: Int,
        startX: Float,
        gap: Float,
        isWordSpacing: Boolean
    ) {
        textLine.startX = absStartX + startX
        if (isWordSpacing) {
            textLine.wordSpacing = gap
        } else {
            textLine.extraLetterSpacingOffsetX = -gap / 2
            textLine.extraLetterSpacing = gap / textPaint.textSize
        }
    }

    /**
     * 无缩进,两端对齐
     */
    private suspend fun addCharsToLineMiddle(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        words: List<String>,
        textPaint: TextPaint,
        /**自然排版长度**/
        desiredWidth: Float,
        /**起始x坐标**/
        startX: Float,
        textWidths: List<Float>,
        srcList: LinkedList<String>?,
        clickList: LinkedList<String?>?,
        /**挤压过的标点在列内的绘制偏移,与 words 同下标*/
        drawOffsets: FloatArray?
    ) {
        if (!textFullJustify) {
            addCharsToLineNatural(
                book, absStartX, textLine, words,
                startX, false, textWidths, srcList,
                clickList, drawOffsets = drawOffsets
            )
            return
        }
        LineColumnLayout.justified(
            words, textWidths, visibleWidth.toFloat(), desiredWidth, startX,
            onJustify = { x, gap, isWordSpacing ->
                applyJustify(textLine, textPaint, absStartX, x, gap, isWordSpacing)
            }
        ) { index, xStart, xEnd, _ ->
            addCharToLine(
                book, absStartX, textLine, words[index], drawOffsets?.get(index) ?: 0f,
                xStart, xEnd, index + 1 == words.size, srcList,
                clickList
            )
        }
        exceed(absStartX, textLine, words)
    }

    /**
     * 自然排列
     */
    private suspend fun addCharsToLineNatural(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        words: List<String>,
        startX: Float,
        hasIndent: Boolean,
        textWidths: List<Float>,
        srcList: LinkedList<String>?,
        clickList: LinkedList<String?>?,
        hangingWidth: Float = 0f,
        /**挤压过的标点在列内的绘制偏移,与 words 同下标*/
        drawOffsets: FloatArray? = null
    ) {
        textLine.startX = absStartX + startX
        LineColumnLayout.natural(
            textWidths, startX, hasIndent, paragraphIndent.length, hangingWidth,
            onIndentWidth = { textLine.indentWidth = it }
        ) { index, xStart, xEnd, kind ->
            if (kind == LineColumnLayout.kindHanging) {
                //段首标点悬挂到缩进内,正文首字与其他段落对齐
                textLine.hangingPunctuation = true
            }
            addCharToLine(
                book, absStartX, textLine, words[index], drawOffsets?.get(index) ?: 0f,
                xStart, xEnd, index + 1 == words.size, srcList, clickList
            )
        }
        exceed(absStartX, textLine, words)
    }

    /**
     * 添加字符
     */
    private suspend fun addCharToLine(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        char: String,
        /**挤压过的标点在列内的绘制偏移*/
        drawOffset: Float,
        xStart: Float,
        xEnd: Float,
        isLineEnd: Boolean,
        srcList: LinkedList<String>?,
        clickList: LinkedList<String?>?
    ) {
        val column = when {
            !srcList.isNullOrEmpty() && (char == srcReplaceStr || char == reviewStr) -> {
                val src = srcList.removeFirst()
                val click = clickList?.removeFirst()
                ImageProvider.cacheImage(book, src, ReadBook.bookSource)
                ImageColumn(
                    start = absStartX + xStart,
                    end = absStartX + xEnd,
                    src = src,
                    click = click
                )
            }
//            isLineEnd && char == ChapterProvider.reviewChar -> {
//                ReviewColumn(
//                    start = absStartX + xStart,
//                    end = absStartX + xEnd,
//                    count = 10
//                )
//            }

            else -> {
                TextColumn(
                    start = absStartX + xStart,
                    end = absStartX + xEnd,
                    charData = char,
                    drawOffset = drawOffset
                )
            }
        }
        textLine.addColumn(column)
    }

    /**
     * 超出边界处理
     */
    private fun exceed(absStartX: Int, textLine: TextLine, words: List<String>) {
        var size = words.size
        if (size < 2) return
        val visibleEnd = absStartX + visibleWidth
        val columns = textLine.columns
        var offset = 0
        val endColumn = if (words.last() == " ") {
            size--
            offset++
            columns[columns.lastIndex - 1]
        } else {
            columns.last()
        }
        val endX = endColumn.end.roundToInt()
        if (endX > visibleEnd) {
            textLine.exceed = true
            val cc = (endX - visibleEnd) / size
            for (i in 0..<size) {
                textLine.getColumnReverseAt(i, offset).let {
                    val py = cc * (size - i)
                    it.start -= py
                    it.end -= py
                }
            }
        }
    }

    private suspend fun prepareNextPageIfNeed(requestHeight: Float = -1f) {
        if (requestHeight > visibleHeight || requestHeight == -1f) {
            val textPage = pendingTextPage
            // 双页的 durY 不正确，可能会小于实际高度
            if (textPage.height < durY) {
                textPage.height = durY
            }
            if (doublePage && absStartX < viewWidth / 2) {
                //当前页面左列结束
                textPage.leftLineSize = textPage.lineSize
                absStartX = viewWidth / 2 + paddingLeft
            } else {
                //当前页面结束,设置各种值
                if (textPage.leftLineSize == 0) {
                    textPage.leftLineSize = textPage.lineSize
                }
                textPage.text = stringBuilder.toString()
                currentCoroutineContext().ensureActive()
                onPageCompleted()
                //新建页面
                pendingTextPage = TextPage()
                stringBuilder.clear()
                absStartX = paddingLeft
            }
            durY = 0f
        }
    }

    private fun allocateFloatArray(size: Int): FloatArray {
        if (size > floatArray.size) {
            floatArray = FloatArray(size)
        }
        return floatArray
    }

    private fun measureTextSplit(
        text: String,
        widthsArray: FloatArray,
        start: Int = 0
    ): Pair<ArrayList<String>, ArrayList<Float>> {
        val length = text.length
        var clusterCount = 0
        for (i in start..<start + length) {
            if (widthsArray[i] > 0) clusterCount++
        }
        val widths = ArrayList<Float>(clusterCount)
        val stringList = ArrayList<String>(clusterCount)
        var i = 0
        while (i < length) {
            val clusterBaseIndex = i++
            widths.add(widthsArray[start + clusterBaseIndex])
            while (i < length && widthsArray[start + i] == 0f && !isZeroWidthChar(text[i])) {
                i++
            }
            stringList.add(text.substring(clusterBaseIndex, i))
        }
        return stringList to widths
    }

    private fun isZeroWidthChar(char: Char): Boolean {
        val code = char.code
        return code == 8203 || code == 8204 || code == 8205 || code == 8288
    }

}

/**
 * 段首标点悬挂规则
 * 段落以缩进+起始引号开头时,引号悬挂于缩进内,使正文首字与其他段落对齐
 */
internal object HangingPunctuationRule {

    private const val hangingChars = "“‘「『﹁﹃\"'"

    fun isHangingChar(char: Char): Boolean {
        return hangingChars.indexOf(char) >= 0
    }

    fun shouldHang(text: String, indent: String): Boolean {
        if (indent.isEmpty()) return false
        if (text.length <= indent.length) return false
        if (!text.startsWith(indent)) return false
        if (!isHangingChar(text[indent.length])) return false
        //排版逐字从左往右累加坐标,右向段落的段首在右侧,悬挂会落在错误的一边
        return !isRightToLeft(text, indent.length + 1)
    }

    /**
     * 段落的首个强方向字符是否为右向,中性字符(数字/标点/空白)继续往后找
     */
    fun isRightToLeft(text: String, start: Int): Boolean {
        var index = start
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            when (Character.getDirectionality(codePoint)) {
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
            }
            index += Character.charCount(codePoint)
        }
        return false
    }
}
