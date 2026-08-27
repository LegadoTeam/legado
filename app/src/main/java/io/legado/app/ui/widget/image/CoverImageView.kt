package io.legado.app.ui.widget.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatImageView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import io.legado.app.constant.AppPattern
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.lib.theme.accentColor
import io.legado.app.model.BookCover
import io.legado.app.utils.textHeight
import io.legado.app.utils.toStringArray
import android.view.ViewOutlineProvider
import androidx.collection.LruCache
import androidx.core.graphics.createBitmap
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.lib.theme.backgroundColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import splitties.init.appCtx

private const val HORIZONTAL_TITLE_MAX_LINES = 4

internal fun normalizeCoverText(value: String?, keepPunctuation: Boolean): String? =
    value?.let { text ->
        if (keepPunctuation) text.trim() else text.replace(AppPattern.bdRegex, "").trim()
    }

internal fun coverBitmapCacheKey(
    name: String,
    author: String?,
    width: Int,
    height: Int,
    horizontal: Boolean,
    drawAuthor: Boolean,
    backgroundColor: Int,
    accentColor: Int
): String = buildString {
    append(name.length).append(':').append(name)
    append('|')
    if (author == null) {
        append("-1:")
    } else {
        append(author.length).append(':').append(author)
    }
    append('|').append(width).append('x').append(height)
    append('|').append(if (horizontal) 'h' else 'v')
    append('|').append(if (drawAuthor) 'a' else 'n')
    append('|').append(backgroundColor).append(',').append(accentColor)
}

internal fun coverTitleTextSize(
    viewWidth: Float,
    viewHeight: Float,
    characterCount: Int,
    largeTextHeight: Float
): Float = if (
    (characterCount - 2) * largeTextHeight > viewHeight * 0.7f ||
    (characterCount - 3) * largeTextHeight > viewHeight * 0.6f
) {
    viewWidth / 9
} else {
    viewWidth / 7
}

internal fun coverTitleColumnStartY(
    top: Float,
    maxColumnBottom: Float,
    columnBottom: Float
): Float = top + (maxColumnBottom - columnBottom).coerceAtLeast(0f)

internal fun coverTitleColumnOffsets(
    characterCount: Int,
    viewHeight: Float,
    textHeight: Float
): List<List<Float>> {
    if (characterCount <= 0 || viewHeight <= 0f || textHeight <= 0f) return emptyList()
    val columns = mutableListOf<List<Float>>()
    var column = mutableListOf<Float>()
    var columnOffset = 0f
    var line = 0
    var nextY = viewHeight * 0.2f
    repeat(characterCount) { index ->
        column += columnOffset
        columnOffset += textHeight
        nextY += textHeight
        val remaining = characterCount - index - 1
        if (nextY > viewHeight * 0.9f) {
            if (remaining == 1) {
                nextY -= textHeight / 5
                columnOffset -= textHeight / 5
            } else if (remaining > 0) {
                columns += column.toList()
                column = mutableListOf()
                columnOffset = 0f
                line++
                nextY = viewHeight * 0.2f + textHeight * line
            }
        } else if (nextY > viewHeight * 0.8f && remaining > 2) {
            columns += column.toList()
            column = mutableListOf()
            columnOffset = 0f
            line++
            nextY = viewHeight * 0.2f + textHeight * line
        }
    }
    if (column.isNotEmpty()) columns += column.toList()
    return columns
}

/**
 * 封面
 */
@Suppress("unused")
class CoverImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {
    companion object {
        private val nameBitmapCache by lazy { LruCache<String, Bitmap>(33) }
        private val needNameBitmap by lazy { LruCache<String, Boolean>(99) }
    }
    private var currentJob: Job? = null
    @Volatile
    private var currentNameBitmap: Pair<String, Bitmap>? = null
    private val triggerChannel = Channel<Unit>(Channel.CONFLATED)
    var bitmapPath: String? = null
        private set
    private var name: String? = null
    private var author: String? = null
    private var sourceName: String? = null
    private var sourceAuthor: String? = null
    private var normalizedKeepPunctuation = BookCover.keepPunctuation
    private var nameHeight = 0f
    private var authorHeight = 0f
    override fun setLayoutParams(params: ViewGroup.LayoutParams?) {
        if (params != null) {
            val width = params.width
            if (width >= 0) {
                params.height = width * 4 / 3
            } else {
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
        super.setLayoutParams(params)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val measuredHeight = measuredWidth * 4 / 3
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            currentJob?.cancel()
            currentNameBitmap = null
        }
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, w, h, 12f)
            }
        }
        clipToOutline = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateNormalizedText()
        val drawBookName = BookCover.drawBookName
        val drawBookAuthor = BookCover.drawBookAuthor
        if (!drawBookName) return
        val currentName = this.name ?: return
        if (AppConfig.useDefaultCover || needNameBitmap[bitmapPath.toString()] == true) {
            val currentAuthor = this.author
            val backgroundColor = appCtx.backgroundColor
            val accentColor = appCtx.accentColor
            val cacheKey = coverBitmapCacheKey(
                currentName,
                currentAuthor,
                width,
                height,
                BookCover.drawBookNameHorizontal,
                drawBookAuthor,
                backgroundColor,
                accentColor
            )
            val cacheBitmap = getNameBitmap(cacheKey)
            if (cacheBitmap != null) {
                canvas.drawBitmap(cacheBitmap, 0f, 0f, null)
                return
            }
            drawNameAuthor(currentName, currentAuthor, backgroundColor, accentColor, false)
        }
    }

    private fun getNameBitmap(cacheKey: String): Bitmap? {
        val currentBitmap = currentNameBitmap
        if (currentBitmap?.first == cacheKey) return currentBitmap.second
        return nameBitmapCache[cacheKey]?.also {
            currentNameBitmap = cacheKey to it
        }
    }

    private fun updateNormalizedText() {
        val keepPunctuation = BookCover.keepPunctuation
        if (keepPunctuation == normalizedKeepPunctuation) return
        val currentName = normalizeCoverText(sourceName, keepPunctuation)
        val currentAuthor = normalizeCoverText(sourceAuthor, keepPunctuation)
        if (name != currentName || author != currentAuthor) {
            currentNameBitmap = null
        }
        name = currentName
        author = currentAuthor
        normalizedKeepPunctuation = keepPunctuation
    }

    private fun drawNameAuthor(
        name: String,
        author: String?,
        backgroundColor: Int = appCtx.backgroundColor,
        accentColor: Int = appCtx.accentColor,
        asyncAwait: Boolean = true
    ) {
        generateCoverAsync(
            name,
            author,
            backgroundColor,
            accentColor,
            BookCover.drawBookNameHorizontal,
            BookCover.drawBookAuthor,
            asyncAwait
        )
    }
    private fun generateCoverAsync(
        name: String,
        author: String?,
        backgroundColor: Int,
        accentColor: Int,
        horizontal: Boolean,
        drawAuthor: Boolean,
        asyncAwait: Boolean
    ) {
        currentJob?.cancel()
        val requestedBitmapPath = bitmapPath
        currentJob = CoroutineScope(Dispatchers.Default).launch {
            try {
                if (asyncAwait) {
                    withTimeoutOrNull(1200) {
                        triggerChannel.receive()
                    }
                    ensureActive()
                }
                if (width == 0) {
                    var attempts = 0
                    do {
                        delay(1L)
                        attempts++
                    } while (width == 0 && attempts < 2000)
                }
                ensureActive()
                val renderWidth = width
                val renderHeight = height
                if (renderWidth <= 0 || renderHeight <= 0) return@launch
                val cacheKey = coverBitmapCacheKey(
                    name,
                    author,
                    renderWidth,
                    renderHeight,
                    horizontal,
                    drawAuthor,
                    backgroundColor,
                    accentColor
                )
                if (getNameBitmap(cacheKey) != null) {
                    postInvalidate()
                    return@launch
                }
                val bitmap = generateCoverBitmap(
                    name,
                    author,
                    renderWidth,
                    renderHeight,
                    horizontal,
                    drawAuthor,
                    backgroundColor,
                    accentColor
                )
                ensureActive()
                needNameBitmap.put(requestedBitmapPath.toString(), true)
                nameBitmapCache.put(cacheKey, bitmap)
                currentNameBitmap = cacheKey to bitmap
                postInvalidate()
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun generateCoverBitmap(
        name: String?,
        author: String?,
        renderWidth: Int,
        renderHeight: Int,
        horizontal: Boolean,
        drawAuthor: Boolean,
        backgroundColor: Int,
        accentColor: Int
    ): Bitmap {
        val viewWidth = renderWidth.toFloat()
        val viewHeight = renderHeight.toFloat()
        val bitmap = createBitmap(renderWidth, renderHeight)
        val bitmapCanvas = Canvas(bitmap)
        var startX = renderWidth * 0.2f
        if (horizontal) {
            drawHorizontalTextCover(
                bitmapCanvas,
                name,
                author,
                backgroundColor,
                accentColor,
                drawAuthor,
                viewWidth,
                viewHeight
            )
            return bitmap
        }
        val namePaint = TextPaint().apply {
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        name?.toStringArray()?.let { name ->
            namePaint.textSize = viewWidth / 7
            namePaint.textSize = coverTitleTextSize(
                viewWidth,
                viewHeight,
                name.size,
                namePaint.textHeight
            )
            namePaint.strokeWidth = namePaint.textSize / 6
            val titleColumns = coverTitleColumnOffsets(
                name.size,
                viewHeight,
                namePaint.textHeight
            )
            val maxColumnBottom = titleColumns.maxOfOrNull { it.last() } ?: 0f
            var nameIndex = 0
            titleColumns.forEachIndexed { columnIndex, offsets ->
                startX = renderWidth * 0.2f + namePaint.textSize * columnIndex
                val startY = coverTitleColumnStartY(
                    viewHeight * 0.2f,
                    maxColumnBottom,
                    offsets.last()
                )
                offsets.forEach { offsetY ->
                    val char = name[nameIndex++]
                    namePaint.color = backgroundColor
                    namePaint.style = Paint.Style.STROKE
                    bitmapCanvas.drawText(char, startX, startY + offsetY, namePaint)
                    namePaint.color = accentColor
                    namePaint.style = Paint.Style.FILL
                    bitmapCanvas.drawText(char, startX, startY + offsetY, namePaint)
                }
            }
        }
        if (!drawAuthor){
            return bitmap
        }
        val authorPaint = TextPaint(namePaint).apply {
            typeface = Typeface.DEFAULT
        }
        author?.toStringArray()?.let { author ->
            authorPaint.textSize = viewWidth / 10
            authorPaint.strokeWidth = authorPaint.textSize / 5
            startX = renderWidth * 0.8f
            var startY = viewHeight * 0.95f - author.size * authorPaint.textHeight
            startY = maxOf(startY, viewHeight * 0.3f)
            author.forEach {
                authorPaint.color = backgroundColor
                authorPaint.style = Paint.Style.STROKE
                bitmapCanvas.drawText(it, startX, startY, authorPaint)
                authorPaint.color = accentColor
                authorPaint.style = Paint.Style.FILL
                bitmapCanvas.drawText(it, startX, startY, authorPaint)
                startY += authorPaint.textHeight
                if (startY > viewHeight * 0.95) {
                    return@let
                }
            }
        }
        return bitmap
    }

    private fun drawHorizontalTextCover(
        canvas: Canvas,
        name: String?,
        author: String?,
        backgroundColor: Int,
        accentColor: Int,
        drawAuthor: Boolean,
        viewWidth: Float,
        viewHeight: Float
    ) {
        val basePaint = TextPaint().apply {
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }
        name?.takeIf { it.isNotEmpty() }?.let { title ->
            val titleWidth = (viewWidth * 0.78f).toInt().coerceAtLeast(1)
            val titlePaint = TextPaint(basePaint).apply {
                textAlign = Paint.Align.LEFT
                textSize = viewWidth / 7
                strokeWidth = textSize / 6
            }
            var titleLayout = horizontalTitleLayout(title, titlePaint, titleWidth)
            if (titleLayout.lineCount > 1 || titlePaint.measureText(title) > titleWidth) {
                titlePaint.textSize = viewWidth / 9
                titlePaint.strokeWidth = titlePaint.textSize / 6
                titleLayout = horizontalTitleLayout(title, titlePaint, titleWidth)
            }
            val titleX = (viewWidth - titleWidth) / 2f
            val titleY = viewHeight * 0.1f
            canvas.save()
            canvas.translate(titleX, titleY)
            titlePaint.color = backgroundColor
            titlePaint.style = Paint.Style.STROKE
            titleLayout.draw(canvas)
            titlePaint.color = accentColor
            titlePaint.style = Paint.Style.FILL
            titleLayout.draw(canvas)
            canvas.restore()
        }

        if (!drawAuthor) return
        author?.takeIf { it.isNotEmpty() }?.let { authorText ->
            val authorWidth = viewWidth * 0.65f
            val authorPaint = TextPaint(basePaint).apply {
                typeface = Typeface.DEFAULT
                textAlign = Paint.Align.RIGHT
                textSize = viewWidth / 10
                strokeWidth = textSize / 5
            }
            while (authorPaint.textSize > viewWidth / 16 &&
                authorPaint.measureText(authorText) > authorWidth
            ) {
                authorPaint.textSize -= 0.5f
                authorPaint.strokeWidth = authorPaint.textSize / 5
            }
            val displayAuthor = TextUtils.ellipsize(
                authorText,
                authorPaint,
                authorWidth,
                TextUtils.TruncateAt.END
            ).toString()
            val authorX = viewWidth * 0.9f
            val authorY = viewHeight * 0.92f
            authorPaint.color = backgroundColor
            authorPaint.style = Paint.Style.STROKE
            canvas.drawText(displayAuthor, authorX, authorY, authorPaint)
            authorPaint.color = accentColor
            authorPaint.style = Paint.Style.FILL
            canvas.drawText(displayAuthor, authorX, authorY, authorPaint)
        }
    }

    private fun horizontalTitleLayout(
        title: String,
        paint: TextPaint,
        width: Int
    ): StaticLayout = StaticLayout.Builder
        .obtain(title, 0, title.length, paint, width)
        .setAlignment(Layout.Alignment.ALIGN_CENTER)
        .setIncludePad(false)
        .setMaxLines(HORIZONTAL_TITLE_MAX_LINES)
        .setEllipsize(TextUtils.TruncateAt.END)
        .build()

    fun setHeight(height: Int) {
        val width = height * 3 / 4
        minimumWidth = width
    }

    private val glideListener by lazy {
        object : RequestListener<Drawable> {

            override fun onLoadFailed(
                e: GlideException?,
                model: Any?,
                target: Target<Drawable>,
                isFirstResource: Boolean
            ): Boolean {
                triggerChannel.trySend(Unit)
                needNameBitmap.put(bitmapPath.toString(), true)
                return false
            }

            override fun onResourceReady(
                resource: Drawable,
                model: Any,
                target: Target<Drawable>?,
                dataSource: DataSource,
                isFirstResource: Boolean
            ): Boolean {
                currentJob?.cancel()
                currentJob = null
                needNameBitmap.remove(bitmapPath.toString())
                invalidate()
                return false
            }

        }
    }

    fun load(
        searchBook: SearchBook,
        loadOnlyWifi: Boolean = false,
        fragment: Fragment? = null,
        lifecycle: Lifecycle? = null
    ) {
        load(searchBook.coverUrl, searchBook.name, searchBook.author, loadOnlyWifi, searchBook.origin, fragment, lifecycle)
    }

    fun load(
        book: Book,
        loadOnlyWifi: Boolean = false,
        fragment: Fragment? = null,
        lifecycle: Lifecycle? = null,
        onLoadFinish: (() -> Unit)? = null
    ) {
        load(
            book.getDisplayCover(),
            book.name,
            book.author,
            loadOnlyWifi,
            book.getCoverSourceOrigin(),
            fragment,
            lifecycle,
            onLoadFinish
        )
    }

    fun load(
        path: String? = null,
        name: String? = null,
        author: String? = null,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
        fragment: Fragment? = null,
        lifecycle: Lifecycle? = null,
        onLoadFinish: (() -> Unit)? = null
    ) {
        currentJob?.cancel()
        currentJob = null
        triggerChannel.tryReceive()
        sourceName = name
        sourceAuthor = author
        normalizedKeepPunctuation = BookCover.keepPunctuation
        val currentAuthor = normalizeCoverText(author, BookCover.keepPunctuation)
        val currentName = normalizeCoverText(name, BookCover.keepPunctuation)
        val currentPath = path?.takeIf { it.isNotBlank() }
        if (this.name != currentName || this.author != currentAuthor) {
            currentNameBitmap = null
        }
        this.author = currentAuthor
        this.name = currentName
        this.bitmapPath = currentPath
        if (AppConfig.useDefaultCover) {
            ImageLoader.load(context, BookCover.defaultDrawable)
                .centerCrop()
                .into(this)
        } else {
            if (currentPath == null) {
                needNameBitmap.put(currentPath.toString(), true)
                ImageLoader.load(context, BookCover.defaultDrawable)
                    .centerCrop()
                    .into(this)
                invalidate()
                onLoadFinish?.invoke()
                return
            }
            if (BookCover.drawBookName && currentName != null) {
                drawNameAuthor(currentName, currentAuthor, asyncAwait = true)
            }
            var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, loadOnlyWifi)
            if (sourceOrigin != null) {
                options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
            }
            var builder = if (fragment != null && lifecycle != null) {
                ImageLoader.load(fragment, lifecycle, currentPath)
            } else {
                ImageLoader.load(context, currentPath)//Glide自动识别http://,content://和file://
            }
            builder = builder.apply(options)
                .placeholder(BookCover.defaultDrawable)
                .error(BookCover.defaultDrawable)
                .listener(glideListener)
            if (onLoadFinish != null) {
                builder = builder.addListener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable?>,
                        isFirstResource: Boolean
                    ): Boolean {
                        onLoadFinish.invoke()
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        onLoadFinish.invoke()
                        return false
                    }
                })
            }
            builder
                .centerCrop()
                .into(this)
        }
    }

    override fun onDetachedFromWindow() {
        currentJob?.cancel()
        currentJob = null
        super.onDetachedFromWindow()
    }

}
