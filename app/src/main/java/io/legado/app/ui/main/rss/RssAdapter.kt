package io.legado.app.ui.main.rss

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.RssSource
import io.legado.app.databinding.ItemRssBinding
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.ui.widget.popupActionMenu
import splitties.views.onLongClick

class RssAdapter(
    context: Context,
    private val fragment: Fragment,
    private val callBack: CallBack,
    private val lifecycle: Lifecycle
) : RecyclerAdapter<RssSource, ItemRssBinding>(context) {

    override fun getViewBinding(parent: ViewGroup): ItemRssBinding {
        return ItemRssBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemRssBinding,
        item: RssSource,
        payloads: MutableList<Any>
    ) {
        binding.apply {
            tvName.text = item.sourceName
            val options = RequestOptions()
                .set(OkHttpModelLoader.sourceOriginOption, item.sourceUrl)
            ImageLoader.load(fragment, lifecycle, item.sourceIcon)
                .apply(options)
                .centerCrop()
                .placeholder(R.drawable.image_rss)
                .error(R.drawable.image_rss)
                .into(ivIcon)
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemRssBinding) {
        binding.apply {
            root.setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let {
                    callBack.openRss(it)
                }
            }
            root.onLongClick {
                getItemByLayoutPosition(holder.layoutPosition)?.let {
                    showMenu(ivIcon, it)
                }
            }
        }
    }

    private fun showMenu(view: View, rssSource: RssSource) {
        popupActionMenu(context) {
            item(context.getString(R.string.edit), "edit")
            item(context.getString(R.string.to_top), "top")
            item(context.getString(R.string.login), "login", !rssSource.loginUrl.isNullOrBlank())
            item(context.getString(R.string.disable_source), "disable")
            item(context.getString(R.string.delete), "delete")
            danger("delete")
        }.show(view) { action ->
            when (action) {
                "edit" -> callBack.edit(rssSource)
                "top" -> callBack.toTop(rssSource)
                "login" -> callBack.login(rssSource)
                "disable" -> callBack.disable(rssSource)
                "delete" -> callBack.del(rssSource)
            }
        }
    }

    interface CallBack {
        fun openRss(rssSource: RssSource)
        fun edit(rssSource: RssSource)
        fun toTop(rssSource: RssSource)
        fun login(rssSource: RssSource)
        fun del(rssSource: RssSource)
        fun disable(rssSource: RssSource)
    }
}
