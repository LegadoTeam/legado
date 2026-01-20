package io.legado.app.help

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.text.Html
import android.util.LruCache
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.paramPattern
import io.legado.app.utils.GSON
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fromJsonObject
import java.lang.ref.WeakReference
import io.legado.app.utils.lifecycle
import java.io.ByteArrayInputStream
import kotlin.io.encoding.Base64
import io.legado.app.utils.SvgUtils
import kotlin.text.dropLast
import kotlin.text.endsWith
import kotlin.text.toIntOrNull

class GlideImageGetter(
    context: Context,
    textView: TextView,
    private  val lifecycle: Lifecycle
) : Html.ImageGetter, Drawable.Callback {
    companion object {
        private val urlStyleCache = LruCache<String, Map<String, String>>(99)
    }
    private val textViewRef = WeakReference(textView)
    private val contextRef = WeakReference(context)
    private val gifDrawables = mutableSetOf<GlideUrlDrawable>()
    private val emptyDrawable by lazy {
        object : Drawable() {
            override fun draw(canvas: Canvas) = Unit
            override fun setAlpha(alpha: Int) = Unit
            override fun setColorFilter(colorFilter: ColorFilter?) = Unit
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSPARENT
            override fun getIntrinsicWidth(): Int = 0
            override fun getIntrinsicHeight(): Int = 0
        }
    }
    private val availableWidth by lazy {
        val textView = textViewRef.get() ?: return@lazy 0
        textView.width - textView.paddingLeft - textView.paddingRight - 8.dpToPx() //8是为了文字对齐额外的右边距
    }

    override fun getDrawable(source: String?): Drawable {
        val context = contextRef.get()
        if (context == null || source.isNullOrBlank()) {
            return emptyDrawable
        }
        if (source.startsWith("data:image/svg")) {
            var data:String? = null
            var style:String? = null
            var width:String? = null
            val urlMatcher = paramPattern.matcher(source)
            if (urlMatcher.find()) {
                val urlOptionStr = source.substring(urlMatcher.end())
                GSON.fromJsonObject<Map<String, String>>(urlOptionStr).getOrNull()?.let { map ->
                    map.forEach { (key, value) ->
                        when (key) {
                            "style" -> style = value
                            "width" -> width = value
                        }
                    }
                }
                data = source.take(urlMatcher.start())
            }
            val inputStream = ByteArrayInputStream(Base64.decode((data ?: source).substringAfter(",")))
            val (pictureDrawable, size) = SvgUtils.createDrawable(inputStream) ?: return emptyDrawable
            val imgWidth = if (width?.endsWith("%")  == true) {
                val sWidth = width.dropLast(1).toIntOrNull() ?: 80
                val displayMetrics = context.resources.displayMetrics
                displayMetrics.widthPixels * sWidth / 100
            } else {
                val sWidth = width?.toIntOrNull() ?: 0
                if (sWidth > 0) {
                    sWidth
                } else {
                    size.width
                }
            }
            val showWidth = availableWidth.takeIf { it in 1..<imgWidth } ?: imgWidth
            val showHeight = (size.height * showWidth / size.width.toFloat()).toInt()
            val left = when (style) {
                "center" -> (availableWidth - showWidth) / 2
                "right" -> availableWidth - showWidth
                else -> 0
            }
            pictureDrawable.setBounds(left, 0, left + showWidth, showHeight)
            return pictureDrawable
        }
        urlStyleCache[source] ?: run {
            val urlMatcher = paramPattern.matcher(source)
            if (urlMatcher.find()) {
                val urlOptionStr = source.substring(urlMatcher.end())
                GSON.fromJsonObject<Map<String, String>>(urlOptionStr).getOrNull()?.let { map ->
                    urlStyleCache.put(source, map.filterKeys { it in listOf("style", "width") })
                    return@run
                }
            }
            urlStyleCache.put(source, emptyMap())
        }
        val urlDrawable = GlideUrlDrawable()
        val target = ImageTarget(urlDrawable, source)
        Glide.with(context).lifecycle(lifecycle)
            .load(source)
            .into(target)
        return urlDrawable
    }

    override fun invalidateDrawable(who: Drawable) {
        textViewRef.get()?.invalidate()
    }

    override fun scheduleDrawable(
        who: Drawable,
        what: Runnable,
        `when`: Long
    ) {
    }

    override fun unscheduleDrawable(
        who: Drawable,
        what: Runnable
    ) {
    }

    private inner class GlideUrlDrawable(): Drawable(), Drawable.Callback {
        private var mDrawable: Drawable? = null
        private var mBounds = Rect()

        fun setDrawable(drawable: Drawable?) {
            mDrawable?.callback = null
            drawable?.callback = this
            mDrawable = drawable
        }

        fun clear() {
            (mDrawable as? GifDrawable)?.apply{
                stop()
                clearAnimationCallbacks()
            }
            mDrawable?.callback = null
            mDrawable = null
        }

        override fun draw(canvas: Canvas) {
            mDrawable?.draw(canvas)
        }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            mBounds.set(bounds)
            mDrawable?.bounds = bounds
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int {
            return mDrawable?.opacity ?: PixelFormat.TRANSLUCENT
        }

        override fun setAlpha(alpha: Int) {
            mDrawable?.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            mDrawable?.colorFilter = colorFilter
        }

        override fun getIntrinsicWidth(): Int {
            return mDrawable?.intrinsicWidth ?: 0
        }

        override fun getIntrinsicHeight(): Int {
            return mDrawable?.intrinsicHeight ?: 0
        }

        override fun invalidateDrawable(who: Drawable) {
            callback?.invalidateDrawable(who)
        }

        override fun scheduleDrawable(
            who: Drawable,
            what: Runnable,
            `when`: Long
        ) {
            callback?.scheduleDrawable(who, what, `when`)
        }

        override fun unscheduleDrawable(
            who: Drawable,
            what: Runnable
        ) {
            callback?.unscheduleDrawable(who, what)
        }
    }

    private inner class ImageTarget(
        private val urlDrawable: GlideUrlDrawable,
        private val source: String
    ) : CustomTarget<Drawable>() {

        override fun onResourceReady(
            drawable: Drawable,
            transition: Transition<in Drawable>?
        ) {
            val textView = textViewRef.get() ?: return
            val context = contextRef.get() ?: return
            val drawableWidth = drawable.intrinsicWidth.coerceAtLeast(1)
            val drawableHeight = drawable.intrinsicHeight.coerceAtLeast(1)
            val style = urlStyleCache[source]
            val styleWidth = style?.get("width")
            val imgWidth = if (styleWidth?.endsWith("%")  == true) {
                val sWidth = styleWidth.dropLast(1).toIntOrNull() ?: 80
                val displayMetrics = context.resources.displayMetrics
                displayMetrics.widthPixels * sWidth / 100
            } else {
                val sWidth = styleWidth?.toIntOrNull() ?: 0
                if (sWidth > 0) {
                    sWidth
                } else {
                    drawableWidth
                }
            }
            val showWidth = availableWidth.takeIf { it in 1..<imgWidth } ?: imgWidth
            val showHeight = (drawableHeight * showWidth / drawableWidth.toFloat()).toInt()
            val styleType = style?.get("style")
            val left = when (styleType) {
                "center" -> (availableWidth - showWidth) / 2
                "right" -> availableWidth - showWidth
                else -> 0
            }
            urlDrawable.setDrawable(drawable)
            urlDrawable.setBounds(left, 0, left + showWidth, showHeight)
            if (drawable is GifDrawable) {
                urlDrawable.callback = this@GlideImageGetter
                drawable.start()
                gifDrawables.add(urlDrawable)
            }
            textView.text = textView.text
        }

        override fun onLoadCleared(placeholder: Drawable?) {
            urlDrawable.setDrawable(placeholder)
        }

        override fun onLoadFailed(errorDrawable: Drawable?) {
            urlDrawable.setDrawable(errorDrawable)
        }
    }

    fun clear() {
        gifDrawables.forEach {
            it.clear()
        }
        gifDrawables.clear()
        contextRef.clear()
        textViewRef.clear()
    }
}