package io.legado.app.ui.book.toc

import android.content.Context
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import androidx.core.view.updatePaddingRelative
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.databinding.ItemChapterListBinding
import io.legado.app.model.localBook.PdfOutlineNode
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.gone
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.visible

internal class PdfOutlineAdapter(
    context: Context,
    private val open: (PdfOutlineNode) -> Unit,
    private val toggle: (Int) -> Unit,
) : DiffRecyclerAdapter<PdfOutlineRow, ItemChapterListBinding>(context) {
    override val diffItemCallback = object : DiffUtil.ItemCallback<PdfOutlineRow>() {
        override fun areItemsTheSame(oldItem: PdfOutlineRow, newItem: PdfOutlineRow) =
            oldItem.node.id == newItem.node.id
        override fun areContentsTheSame(oldItem: PdfOutlineRow, newItem: PdfOutlineRow) = oldItem == newItem
    }

    override fun getViewBinding(parent: ViewGroup) = ItemChapterListBinding.inflate(inflater, parent, false)

    override fun convert(holder: ItemViewHolder, binding: ItemChapterListBinding, item: PdfOutlineRow,
                         payloads: MutableList<Any>) = binding.run {
        val node = item.node
        tvChapterItem.updatePaddingRelative(start = (12 + minOf(node.depth, 8) * 10).dpToPx())
        tvChapterName.text = title(node)
        tvChapterName.setTextColor(context.getCompatColor(R.color.primaryText))
        tvChapterName.isSingleLine = false
        tvChapterName.maxLines = 2
        tvChapterName.ellipsize = TextUtils.TruncateAt.END
        tvTag.text = node.pageIndex?.let { context.getString(R.string.pdf_outline_page, it + 1) }
        tvTag.visible(node.pageIndex != null)
        tvWordCount.gone()
        ivLocked.gone()
        ivChecked.gone()
        cbSelected.gone()
        ivVolumeArrow.visible(item.canToggle)
        ivVolumeArrow.setImageResource(if (item.collapsed) R.drawable.ic_arrow_right else R.drawable.ic_expand_more)
        endActions.updateLayoutParams { width = 48.dpToPx(); height = 48.dpToPx() }
        endActions.isClickable = item.canToggle
        endActions.contentDescription = if (item.canToggle) context.getString(
            if (item.collapsed) R.string.pdf_outline_expand else R.string.pdf_outline_collapse
        ) else null
        endActions.isFocusable = item.canToggle
        endActions.importantForAccessibility = if (item.canToggle) View.IMPORTANT_FOR_ACCESSIBILITY_YES
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemChapterListBinding) {
        holder.itemView.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                if (it.node.pageIndex != null) open(it.node) else if (it.canToggle) toggle(it.node.id)
            }
        }
        binding.endActions.setOnClickListener {
            getItem(holder.layoutPosition)?.takeIf { it.canToggle }?.let { toggle(it.node.id) }
        }
        holder.itemView.setOnLongClickListener {
            getItem(holder.layoutPosition)?.let { context.longToastOnUi(title(it.node)) }
            true
        }
    }

    private fun title(node: PdfOutlineNode) = node.title.ifBlank { context.getString(R.string.pdf_outline_untitled) }
}
