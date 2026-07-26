package io.legado.app.ui.rss.article

import android.content.Context
import android.widget.ImageView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.RssArticle


abstract class BaseRssArticlesAdapter<VB : ViewBinding>(context: Context, val callBack: CallBack) :
    RecyclerAdapter<RssArticle, VB>(context) {
    protected fun clearImage(imageView: ImageView) {
        Glide.with(context).clear(imageView)
        imageView.setImageDrawable(null)
    }

    interface CallBack {
        val isGridLayout: Boolean
        fun readRss(rssArticle: RssArticle)
    }
}
