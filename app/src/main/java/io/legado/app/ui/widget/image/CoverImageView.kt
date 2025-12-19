package io.legado.app.ui.widget.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import splitties.init.appCtx
import java.lang.ref.SoftReference

/**
 * 封面
 */
@Suppress("unused")
class CoverImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {
    companion object {
        private val nameBitmapCache by lazy { LruCache<String, Bitmap>(1024 * 1024 * 99) }
    }
    private val cacheMutex = Mutex()
    private var viewWidth: Float = 0f
    private var viewHeight: Float = 0f
    private var drawName = false
    private var cachedBitmap: SoftReference<Bitmap>? = null
    private var currentJob: Job? = null
    private val triggerChannel = Channel<Unit>(Channel.CONFLATED)
    var bitmapPath: String? = null
        private set
    private var isSaveBook = false
    private var name: String? = null
    private var author: String? = null
    private var nameHeight = 0f
    private var authorHeight = 0f
    private val namePaint by lazy {
        val textPaint = TextPaint()
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.isAntiAlias = true
        textPaint.textAlign = Paint.Align.CENTER
        textPaint
    }
    private val authorPaint by lazy {
        val textPaint = TextPaint()
        textPaint.typeface = Typeface.DEFAULT
        textPaint.isAntiAlias = true
        textPaint.textAlign = Paint.Align.CENTER
        textPaint
    }

    private val backgroundColor by lazy { appCtx.backgroundColor }
    private val accentColor by lazy { appCtx.accentColor }

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
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, w, h, 12f)
            }
        }
        clipToOutline = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (drawName && !isInEditMode) {
            val cacheBitmap = cachedBitmap?.get()
            if (cacheBitmap != null) {
                canvas.drawBitmap(cacheBitmap, 0f, 0f, null)
            } else if (currentJob == null) {
                drawNameAuthor()
            }
        }
    }

    private fun drawNameAuthor() {
        if (!BookCover.drawBookName) return
        var pathName = name ?: return
        if (cachedBitmap != null) {
            invalidate()
            return
        }
        if (BookCover.drawBookAuthor) {
            pathName += author.toString()
        }
        generateCoverAsync(pathName)
    }
    private fun generateCoverAsync(pathName: String) {
        currentJob?.cancel()
        val executeTask: suspend () -> Unit = {
            try {
                var bitmap: Bitmap? = null
                if (isSaveBook) {
                    cacheMutex.withLock {
                        bitmap = nameBitmapCache[pathName + width]
                    }
                }
                if (bitmap == null) {
                    bitmap = generateCoverBitmap(pathName)
                    if (isSaveBook && bitmap != null) {
                        cacheMutex.withLock {
                            nameBitmapCache.put(pathName + width, bitmap)
                        }
                    }
                }
                if (bitmap != null) {
                    cachedBitmap = SoftReference(bitmap)
                    drawName = true
                    withContext(Dispatchers.Main) {
                        invalidate()
                    }
                }
            } catch (e: CancellationException) {
                // 正常取消处理
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                currentJob = null
            }
        }
        currentJob = CoroutineScope(Dispatchers.IO).launch {
            withTimeoutOrNull(1000) {
                triggerChannel.receive()
                while (width <= 0 || height <= 0) {
                    delay(10)
                }
            }
            executeTask()
        }
    }

    private fun generateCoverBitmap(pathName: String): Bitmap? {
        if (width <= 0 || height <= 0) return null
        viewWidth = width.toFloat()
        viewHeight = height.toFloat()
        val bitmap = createBitmap(width, height)
        val bitmapCanvas = Canvas(bitmap)
        var startX = width * 0.2f
        var startY = viewHeight * 0.2f
        name?.toStringArray()?.let { name ->
            var line = 0
            namePaint.textSize = viewWidth / 7
            namePaint.strokeWidth = namePaint.textSize / 6
            name.forEachIndexed { index, char ->
                namePaint.color = backgroundColor
                namePaint.style = Paint.Style.STROKE
                bitmapCanvas.drawText(char, startX, startY, namePaint)
                namePaint.color = accentColor
                namePaint.style = Paint.Style.FILL
                bitmapCanvas.drawText(char, startX, startY, namePaint)
                startY += namePaint.textHeight
                if (startY > viewHeight * 0.9) {
                    if ((name.size - index - 1) == 1) { //只剩一个字
                        startY -= namePaint.textHeight / 5
                        namePaint.textSize = viewWidth / 9
                        return@forEachIndexed
                    }
                    startX += namePaint.textSize
                    line++
                    namePaint.textSize = viewWidth / 10
                    startY = viewHeight * 0.2f + namePaint.textHeight * line
                }
                else if (startY > viewHeight * 0.8 && (name.size - index - 1) > 2) { //剩余字数大于2
                    startX += namePaint.textSize
                    line++
                    namePaint.textSize = viewWidth / 10
                    startY = viewHeight * 0.2f + namePaint.textHeight * line
                }
            }
        }
        if (!BookCover.drawBookAuthor){
            return bitmap
        }
        author?.toStringArray()?.let { author ->
            authorPaint.textSize = viewWidth / 10
            authorPaint.strokeWidth = authorPaint.textSize / 5
            startX = width * 0.8f
            startY = viewHeight * 0.95f - author.size * authorPaint.textHeight
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
                if (!drawName && name != null) {
                    drawName = true
                    triggerChannel.trySend(Unit)
                }
                return false
            }

            override fun onResourceReady(
                resource: Drawable,
                model: Any,
                target: Target<Drawable>?,
                dataSource: DataSource,
                isFirstResource: Boolean
            ): Boolean {
                drawName = false
                currentJob?.cancel()
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
        isSaveBook = true
       load(book.getDisplayCover(), book.name, book.author, loadOnlyWifi, book.origin, fragment, lifecycle, onLoadFinish)
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
        if (author != null) {
            this.author = author.replace(AppPattern.bdRegex, "").trim()
        }
        if (name != null) {
            this.name = name.replace(AppPattern.bdRegex, "").trim()
            drawNameAuthor()
        }
        this.bitmapPath = path
        if (AppConfig.useDefaultCover) {
            ImageLoader.load(context, BookCover.defaultDrawable)
                .centerCrop()
                .into(this)
            drawName = true
            triggerChannel.trySend(Unit)
        } else {
            var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, loadOnlyWifi)
            if (sourceOrigin != null) {
                options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
            }
            var builder = if (fragment != null && lifecycle != null) {
                ImageLoader.load(fragment, lifecycle, path)
            } else {
                ImageLoader.load(context, path)//Glide自动识别http://,content://和file://
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
                        target: Target<Drawable?>?,
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
        super.onDetachedFromWindow()
        currentJob?.cancel()
        currentJob = null
        cachedBitmap = null
    }

}
