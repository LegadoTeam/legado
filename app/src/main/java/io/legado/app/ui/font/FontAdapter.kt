package io.legado.app.ui.font

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppLog
import io.legado.app.databinding.ItemFontBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.*

data class FontItem(
    val doc: FileDoc,
    val privateFolder: Boolean,
)

class FontAdapter(context: Context, private val curFilePath: String, val callBack: CallBack) :
    RecyclerAdapter<FontItem, ItemFontBinding>(context) {

    override fun getViewBinding(parent: ViewGroup): ItemFontBinding {
        return ItemFontBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemFontBinding,
        item: FontItem,
        payloads: MutableList<Any>
    ) {
        val doc = item.doc
        binding.run {
            tvFont.typeface = kotlin.runCatching {
                if (doc.isContentScheme) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.contentResolver
                            .openFileDescriptor(doc.uri, "r")?.use {
                                Typeface.Builder(it.fileDescriptor).build()
                            }
                    } else {
                        Typeface.createFromFile(RealPathUtil.getPath(context, doc.uri))
                    }
                } else {
                    Typeface.createFromFile(doc.uri.path!!)
                }
            }.onFailure {
                it.printOnDebug()
                AppLog.put("读取字体 ${doc.name} 出错\n${it.localizedMessage}", it, true)
            }.getOrNull() ?: Typeface.DEFAULT
            tvFont.text = context.getString(
                if (item.privateFolder) R.string.font_item_private else R.string.font_item_external,
                doc.name,
            )
            root.setOnClickListener { callBack.onFontSelect(doc) }
            val selected = doc.toString() == curFilePath
            ivChecked.visible(selected)
            rootCard.background = GradientDrawable().apply {
                cornerRadius = 4.dpToPx().toFloat()
                setColor(Color.TRANSPARENT)
                if (selected) {
                    setStroke(2.dpToPx(), context.accentColor)
                }
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemFontBinding) {
        holder.itemView.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                callBack.onFontSelect(it.doc)
            }
        }
    }

    interface CallBack {
        fun onFontSelect(docItem: FileDoc)
    }
}
