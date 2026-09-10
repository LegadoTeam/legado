package io.legado.app.ui.rss.source.manage

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.RssSource
import io.legado.app.databinding.ItemRssSourceBinding
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.widget.popupActionMenu
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.ColorUtils
import java.util.Collections


class RssSourceAdapter(context: Context, val callBack: CallBack) :
    RecyclerAdapter<RssSource, ItemRssSourceBinding>(context),
    ItemTouchCallback.Callback {

    private val selected = linkedSetOf<RssSource>()

    val selection: List<RssSource>
        get() {
            return getItems().filter {
                selected.contains(it)
            }
        }

    val diffItemCallback = object : DiffUtil.ItemCallback<RssSource>() {

        override fun areItemsTheSame(oldItem: RssSource, newItem: RssSource): Boolean {
            return oldItem.sourceUrl == newItem.sourceUrl
        }

        override fun areContentsTheSame(oldItem: RssSource, newItem: RssSource): Boolean {
            return oldItem.sourceName == newItem.sourceName
                    && oldItem.sourceGroup == newItem.sourceGroup
                    && oldItem.enabled == newItem.enabled
        }

        override fun getChangePayload(oldItem: RssSource, newItem: RssSource): Any? {
            val payload = Bundle()
            if (oldItem.sourceName != newItem.sourceName
                || oldItem.sourceGroup != newItem.sourceGroup
            ) {
                payload.putBoolean("upName", true)
            }
            if (oldItem.enabled != newItem.enabled) {
                payload.putBoolean("enabled", newItem.enabled)
            }
            if (payload.isEmpty) {
                return null
            }
            return payload
        }
    }

    override fun getViewBinding(parent: ViewGroup): ItemRssSourceBinding {
        return ItemRssSourceBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemRssSourceBinding,
        item: RssSource,
        payloads: MutableList<Any>
    ) {
        binding.run {
            if (payloads.isEmpty()) {
                root.setBackgroundColor(ColorUtils.withAlpha(context.backgroundColor, 0.5f))
                cbSource.text = item.getDisplayNameGroup()
                swtEnabled.isChecked = item.enabled
                cbSource.isChecked = selected.contains(item)
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "upName" -> cbSource.text = item.getDisplayNameGroup()
                            "enabled" -> swtEnabled.isChecked = bundle.getBoolean("enabled")
                            "selected" -> cbSource.isChecked = selected.contains(item)
                        }
                    }
                }
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemRssSourceBinding) {
        binding.apply {
            swtEnabled.setOnUserCheckedChangeListener { checked ->
                getItem(holder.layoutPosition)?.let {
                    callBack.enable(checked, it)
                }
            }
            cbSource.setOnUserCheckedChangeListener { checked ->
                getItem(holder.layoutPosition)?.let {
                    if (checked) {
                        selected.add(it)
                    } else {
                        selected.remove(it)
                    }
                    callBack.upCountView()
                }
            }
            ivEdit.setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.edit(it)
                }
            }
            ivMenuMore.setOnClickListener {
                showMenu(ivMenuMore, holder.layoutPosition)
            }
        }
    }

    override fun onCurrentListChanged() {
        callBack.upCountView()
    }

    fun selectAll() {
        getItems().forEach {
            selected.add(it)
        }
        notifyItemRangeChanged(0, itemCount, Bundle().apply {
            putString("selected", null)
        })
        callBack.upCountView()
    }

    fun revertSelection() {
        getItems().forEach {
            if (selected.contains(it)) {
                selected.remove(it)
            } else {
                selected.add(it)
            }
        }
        notifyItemRangeChanged(0, itemCount, Bundle().apply {
            putString("selected", null)
        })
        callBack.upCountView()
    }

    fun checkSelectedInterval() {
        val selectedPosition = linkedSetOf<Int>()
        getItems().forEachIndexed { index, it ->
            if (selected.contains(it)) {
                selectedPosition.add(index)
            }
        }
        if (selectedPosition.isEmpty()) return
        val minPosition = Collections.min(selectedPosition)
        val maxPosition = Collections.max(selectedPosition)
        val itemCount = maxPosition - minPosition + 1
        for (i in minPosition..maxPosition) {
            getItem(i)?.let {
                selected.add(it)
            }
        }
        notifyItemRangeChanged(minPosition, itemCount, Bundle().apply {
            putString("selected", null)
        })
        callBack.upCountView()
    }

    private fun showMenu(view: View, position: Int) {
        val source = getItem(position) ?: return
        popupActionMenu(context) {
            item(context.getString(R.string.to_top), "top")
            item(context.getString(R.string.to_bottom), "bottom")
            item(context.getString(R.string.delete), "delete")
            danger("delete")
        }.show(view) { action ->
            when (action) {
                "top" -> callBack.toTop(source)
                "bottom" -> callBack.toBottom(source)
                "delete" -> {
                    callBack.del(source)
                    selected.remove(source)
                }
            }
        }
    }

    private var dragStartPosition = RecyclerView.NO_POSITION
    private var draggedKey: String? = null
    private var draggedHolder: RecyclerView.ViewHolder? = null

    override fun canStartDrag() = !listUpdatesPaused

    override fun onDragStarted(viewHolder: RecyclerView.ViewHolder) {
        if (listUpdatesPaused) return
        val position = viewHolder.bindingAdapterPosition
        val source = getItem(position) ?: return
        pauseListUpdates()
        dragStartPosition = position
        draggedKey = source.sourceUrl
        draggedHolder = viewHolder
    }

    override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
        val source = getItem(srcPosition) ?: return false
        if (getItem(targetPosition) == null) return false
        if (draggedHolder == null || source.sourceUrl != draggedKey) {
            return false
        }
        swapItem(srcPosition, targetPosition)
        return true
    }

    override fun onClearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        if (viewHolder !== draggedHolder) return
        val start = dragStartPosition
        val key = draggedKey
        dragStartPosition = RecyclerView.NO_POSITION
        draggedKey = null
        draggedHolder = null
        fun finish() {
            resumeListUpdates()
            callBack.reload()
        }
        val end = viewHolder.bindingAdapterPosition
        val moved = getItem(end)?.takeIf { it.sourceUrl == key }
        if (start == RecyclerView.NO_POSITION || end == RecyclerView.NO_POSITION || start == end || moved == null) {
            finish()
            return
        }
        val after = end > start
        val target = getItem(if (after) end - 1 else end + 1)
        if (target == null) {
            finish()
            return
        }
        callBack.move(moved.sourceUrl, target.sourceUrl, after, ::finish)
    }

    val dragSelectCallback: DragSelectTouchHelper.Callback =
        object : DragSelectTouchHelper.AdvanceCallback<RssSource>(Mode.ToggleAndReverse) {
            override fun currentSelectedId(): MutableSet<RssSource> {
                return selected
            }

            override fun getItemId(position: Int): RssSource {
                return getItem(position)!!
            }

            override fun updateSelectState(position: Int, isSelected: Boolean): Boolean {
                getItem(position)?.let {
                    if (isSelected) {
                        selected.add(it)
                    } else {
                        selected.remove(it)
                    }
                    notifyItemChanged(position, Bundle().apply {
                        putString("selected", null)
                    })
                    callBack.upCountView()
                    return true
                }
                return false
            }
        }

    interface CallBack {
        fun del(source: RssSource)
        fun edit(source: RssSource)
        fun enable(enable: Boolean, source: RssSource)
        fun toTop(source: RssSource)
        fun toBottom(source: RssSource)
        fun move(sourceUrl: String, targetUrl: String, after: Boolean, onFinally: () -> Unit)
        fun reload()
        fun upCountView()
    }
}
