package io.legado.app.ui.book.read.config

import android.content.Context
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ItemBgImageBinding
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.dpToPx

internal class ReviewIconSvgTemplateAdapter(
    context: Context,
    private val textColor: Int,
) : RecyclerAdapter<ReadBookConfig.ReviewIconSvgTemplate, ItemBgImageBinding>(context) {

    private val previewSize = 48.dpToPx()

    override fun getViewBinding(parent: ViewGroup): ItemBgImageBinding {
        return ItemBgImageBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemBgImageBinding,
        item: ReadBookConfig.ReviewIconSvgTemplate,
        payloads: MutableList<Any>
    ) {
        binding.run {
            val previewSvg = item.svg.replace("{{count}}", "88")
            val displayName = item.name.ifBlank {
                context.getString(R.string.review_icon_template_unnamed)
            }
            ivBg.setImageBitmap(
                SvgUtils.createBitmapFromSvgText(previewSvg, previewSize, previewSize)
            )
            ivBg.contentDescription = displayName
            tvName.setTextColor(textColor)
            tvName.text = displayName
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemBgImageBinding) = Unit
}
