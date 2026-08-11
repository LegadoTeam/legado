package io.legado.app.ui.book.changesource

import android.content.Context
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.ItemChapterListBinding
import io.legado.app.lib.theme.ThemeUtils
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.gone
import io.legado.app.utils.visible

class ChangeChapterTocAdapter(context: Context, val callback: Callback) :
    RecyclerAdapter<BookChapter, ItemChapterListBinding>(context) {

    var durChapterIndex = 0
    var batchMode = false
    var selectionEnabled = true
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }
    private val selectedIndices = hashSetOf<Int>()

    val selectedChapters: List<Pair<BookChapter, String?>>
        get() = selectedChapterSourceItems(getItems(), selectedIndices)

    val selectedPositions: List<Int>
        get() = getItems().mapIndexedNotNull { position, chapter ->
            position.takeIf { chapter.index in selectedIndices }
        }

    val selectionCount: Int
        get() = selectedIndices.size

    val lastSelectedPosition: Int
        get() = getItems().indexOfLast { it.index in selectedIndices }

    override fun getViewBinding(parent: ViewGroup): ItemChapterListBinding {
        return ItemChapterListBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemChapterListBinding,
        item: BookChapter,
        payloads: MutableList<Any>
    ) {
        binding.run {
            val isDur = durChapterIndex == item.index
            val isSelected = batchMode && item.index in selectedIndices
            if (isDur) {
                tvChapterName.setTextColor(context.accentColor)
            } else {
                tvChapterName.setTextColor(context.getCompatColor(R.color.primaryText))
            }
            tvChapterName.text = item.title
            if (item.isVolume) {
                //卷名，如第一卷 突出显示
                tvChapterItem.setBackgroundColor(context.getCompatColor(R.color.btn_bg_press))
            } else {
                //普通章节 保持不变
                tvChapterItem.background =
                    ThemeUtils.resolveDrawable(context, android.R.attr.selectableItemBackground)
            }
            if (!item.tag.isNullOrEmpty() && !item.isVolume) {
                //卷名不显示tag(更新时间规则)
                tvTag.text = item.tag
                tvTag.visible()
            } else {
                tvTag.gone()
            }
            ivChecked.setImageResource(R.drawable.ic_check)
            ivChecked.isVisible = !batchMode && isDur
            cbSelected.isVisible = batchMode && !item.isVolume
            cbSelected.isChecked = isSelected
            tvChapterItem.isClickable = !batchMode || (!item.isVolume && selectionEnabled)
            tvChapterItem.isSelected = isSelected
            ViewCompat.setAccessibilityHeading(tvChapterItem, item.isVolume)
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemChapterListBinding) {
        holder.itemView.setOnClickListener {
            val position = holder.bindingAdapterPosition
            getItem(position)?.let { chapter ->
                if (batchMode) {
                    if (chapter.isVolume || !selectionEnabled) return@let
                    if (!selectedIndices.add(chapter.index)) {
                        selectedIndices.remove(chapter.index)
                    }
                    notifyItemChanged(position)
                    callback.selectionChanged()
                } else {
                    callback.clickChapter(chapter, getItem(position + 1)?.url)
                }
            }
        }
    }

    fun clearSelection() {
        if (selectedIndices.isEmpty()) return
        val positions = getItems().mapIndexedNotNull { position, chapter ->
            position.takeIf { chapter.index in selectedIndices }
        }
        selectedIndices.clear()
        positions.forEach(::notifyItemChanged)
        callback.selectionChanged()
    }

    fun selectPositions(positions: Collection<Int>) {
        val items = getItems()
        val nextSelection = positions.mapNotNullTo(hashSetOf()) { position ->
            items.getOrNull(position)?.takeUnless { it.isVolume }?.index
        }
        if (nextSelection == selectedIndices) return
        val changedPositions = items.mapIndexedNotNull { position, chapter ->
            position.takeIf {
                (chapter.index in selectedIndices) != (chapter.index in nextSelection)
            }
        }
        selectedIndices.clear()
        selectedIndices.addAll(nextSelection)
        changedPositions.forEach(::notifyItemChanged)
        callback.selectionChanged()
    }

    interface Callback {
        fun clickChapter(bookChapter: BookChapter, nextChapterUrl: String?)
        fun selectionChanged()
    }
}

internal fun selectedChapterSourceItems(
    chapters: List<BookChapter>,
    selectedIndices: Set<Int>,
): List<Pair<BookChapter, String?>> = chapters.mapIndexedNotNull { position, chapter ->
    if (!chapter.isVolume && chapter.index in selectedIndices) {
        chapter to chapters.getOrNull(position + 1)?.url
    } else {
        null
    }
}
