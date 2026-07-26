package io.legado.app.ui.book.toc

import android.content.Context
import android.view.ViewGroup
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.BookHighlight
import io.legado.app.databinding.ItemHighlightBinding
import io.legado.app.utils.gone
import splitties.views.onLongClick

class HighlightAdapter(context: Context, private val callback: Callback) :
    RecyclerAdapter<BookHighlight, ItemHighlightBinding>(context) {

    override fun getViewBinding(parent: ViewGroup): ItemHighlightBinding {
        return ItemHighlightBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemHighlightBinding,
        item: BookHighlight,
        payloads: MutableList<Any>
    ) {
        binding.tvChapterName.text = item.chapterName
        binding.tvBookText.gone(item.bookText.isEmpty())
        binding.tvBookText.text = item.bookText
        binding.tvNote.gone(item.note.isEmpty())
        binding.tvNote.text = item.note
        val style = item.styleObj()
        val color = style.fill.takeIf { it != 0 }
            ?: style.textColor.takeIf { it != 0 }
            ?: style.underline?.color?.takeIf { it != 0 }
            ?: style.strike?.color?.takeIf { it != 0 }
            ?: style.box?.color?.takeIf { it != 0 }
            ?: style.emphasis?.color?.takeIf { it != 0 }
            ?: 0
        binding.viewColor.setBackgroundColor(color)
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemHighlightBinding) {
        binding.root.setOnClickListener {
            getItem(holder.layoutPosition)?.let(callback::onClick)
        }
        binding.root.onLongClick {
            getItem(holder.layoutPosition)?.let(callback::onLongClick)
        }
    }

    interface Callback {
        fun onClick(highlight: BookHighlight)
        fun onLongClick(highlight: BookHighlight)
    }
}
