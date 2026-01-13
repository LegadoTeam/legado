package io.legado.app.ui.rss.article

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.view.ViewGroup
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.RssArticle
import io.legado.app.databinding.ItemRssArticle3Binding
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.utils.getCompatColor
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import android.graphics.drawable.Drawable
import androidx.collection.LruCache
import com.bumptech.glide.request.target.Target
import io.legado.app.help.CacheManager

class RssArticlesAdapter3(context: Context, callBack: CallBack) :
    BaseRssArticlesAdapter<ItemRssArticle3Binding>(context, callBack) {

    companion object {
        private val imageAspectRatio = LruCache<String, Float>(399)
        private const val KEY_NAME = "img_ar_"
        private const val SAVE_TIME = 60 * 60 * 24 * 20 //20天
        private fun getImageAspectRatio(url: String): Float {
            imageAspectRatio[url]?.let {
                return it
            }
            CacheManager.getFloat(KEY_NAME + url)?.let {
                imageAspectRatio.put(url, it)
                return it
            }
            return 0f
        }
        private fun putImageAspectRatio(url: String, aspectRatio: Float) {
            if (aspectRatio <= 0f) return
            imageAspectRatio.put(url, aspectRatio)
            CacheManager.put(KEY_NAME + url, aspectRatio, SAVE_TIME)
        }
        fun clearImageAspectRatio() {
            imageAspectRatio.evictAll()
        }
    }

    private val orientation = context.resources.configuration.orientation
    private val columnCount = if (orientation ==Configuration.ORIENTATION_LANDSCAPE) 3 else 2
    private var cardWidth = 0
    override fun getViewBinding(parent: ViewGroup): ItemRssArticle3Binding {
        if (cardWidth == 0) {
            val parentWith = parent.width
            cardWidth = (parentWith - (columnCount + 1) * 40) / columnCount
        }
        return ItemRssArticle3Binding.inflate(inflater, parent, false)
    }

    @SuppressLint("CheckResult")
    override fun convert(
        holder: ItemViewHolder,
        binding: ItemRssArticle3Binding,
        item: RssArticle,
        payloads: MutableList<Any>
    ) {
        if (payloads.isNotEmpty()) {
            payloads.forEach { payload ->
                when (payload) {
                    "read" -> {
                        if (item.read) {
                            binding.tvTitle.setTextColor(context.getCompatColor(R.color.tv_text_summary))
                        } else {
                            binding.tvTitle.setTextColor(context.getCompatColor(R.color.primaryText))
                        }
                    }
                    "title" -> {
                        binding.tvTitle.text = item.title
                    }
                }
            }
            return
        }
        binding.run {
            tvTitle.text = item.title
            if (item.read) {
                tvTitle.setTextColor(context.getCompatColor(R.color.tv_text_summary))
            } else {
                tvTitle.setTextColor(context.getCompatColor(R.color.primaryText))
            }
            tvPubDate.text = item.pubDate
            val imageUrl = item.image
            if (imageUrl.isNullOrEmpty()) {
                return
            }
            val options = RequestOptions()
                .set(OkHttpModelLoader.sourceOriginOption, item.origin)
            val imageRequest = ImageLoader.load(context, imageUrl)
                .apply(options)
                .placeholder(R.drawable.transparent_placeholder) //svg图会依靠这个进行尺寸约束
            val layoutParams = imageView.layoutParams
            layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            val aspectRatio = getImageAspectRatio(imageUrl)
            if (aspectRatio == 0f) {
                layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                imageRequest.addListener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        return false
                    }
                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        val width = resource.intrinsicWidth
                        val height = resource.intrinsicHeight
                        if (width > 0 && height > 0) {
                            val aspectRatio = height.toFloat() / width.toFloat()
                            putImageAspectRatio(imageUrl, aspectRatio)
                        }
                        return false
                    }
                })
            } else {
                layoutParams.height = (cardWidth * aspectRatio).toInt()
            }
            imageView.layoutParams = layoutParams
            imageView.adjustViewBounds = true //自动调整ImageView的边界来适应图片的宽高比
            imageRequest.into(imageView)
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemRssArticle3Binding) {
        holder.itemView.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                callBack.readRss(it)
            }
        }
    }

}