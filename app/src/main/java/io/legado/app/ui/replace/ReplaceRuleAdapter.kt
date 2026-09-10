package io.legado.app.ui.replace

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.databinding.ItemReplaceRuleBinding
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.widget.popupActionMenu
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.ColorUtils


class ReplaceRuleAdapter(context: Context, var callBack: CallBack) :
    RecyclerAdapter<ReplaceRule, ItemReplaceRuleBinding>(context),
    ItemTouchCallback.Callback {

    private val selected = linkedSetOf<ReplaceRule>()

    val selection: List<ReplaceRule>
        get() {
            return getItems().filter {
                selected.contains(it)
            }
        }

    val diffItemCallBack = object : DiffUtil.ItemCallback<ReplaceRule>() {

        override fun areItemsTheSame(oldItem: ReplaceRule, newItem: ReplaceRule): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ReplaceRule, newItem: ReplaceRule): Boolean {
            if (oldItem.name != newItem.name) {
                return false
            }
            if (oldItem.group != newItem.group) {
                return false
            }
            if (oldItem.isEnabled != newItem.isEnabled) {
                return false
            }
            return true
        }

        override fun getChangePayload(oldItem: ReplaceRule, newItem: ReplaceRule): Any? {
            val payload = Bundle()
            if (oldItem.name != newItem.name
                || oldItem.group != newItem.group
            ) {
                payload.putBoolean("upName", true)
            }
            if (oldItem.isEnabled != newItem.isEnabled) {
                payload.putBoolean("enabled", newItem.isEnabled)
            }
            if (payload.isEmpty) {
                return null
            }
            return payload
        }
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

    override fun getViewBinding(parent: ViewGroup): ItemReplaceRuleBinding {
        return ItemReplaceRuleBinding.inflate(inflater, parent, false)
    }

    override fun onCurrentListChanged() {
        callBack.upCountView()
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemReplaceRuleBinding,
        item: ReplaceRule,
        payloads: MutableList<Any>
    ) {
        binding.run {
            if (payloads.isEmpty()) {
                root.setBackgroundColor(ColorUtils.withAlpha(context.backgroundColor, 0.5f))
                cbName.text = item.getDisplayNameGroup()
                swtEnabled.isChecked = item.isEnabled
                cbName.isChecked = selected.contains(item)
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "selected" -> cbName.isChecked = selected.contains(item)
                            "upName" -> cbName.text = item.getDisplayNameGroup()
                            "enabled" -> swtEnabled.isChecked = item.isEnabled
                        }
                    }
                }
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemReplaceRuleBinding) {
        binding.apply {
            swtEnabled.setOnUserCheckedChangeListener { isChecked ->
                getItem(holder.layoutPosition)?.let {
                    it.isEnabled = isChecked
                    callBack.update(it)
                }
            }
            ivEdit.setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.edit(it)
                }
            }
            cbName.setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    if (cbName.isChecked) {
                        selected.add(it)
                    } else {
                        selected.remove(it)
                    }
                }
                callBack.upCountView()
            }
            ivMenuMore.setOnClickListener {
                showMenu(ivMenuMore, holder.layoutPosition)
            }
        }
    }

    private fun showMenu(view: View, position: Int) {
        val item = getItem(position) ?: return
        popupActionMenu(context) {
            item(context.getString(R.string.to_top), "top")
            item(context.getString(R.string.to_bottom), "bottom")
            item(context.getString(R.string.delete), "delete")
            danger("delete")
        }.show(view) { action ->
            when (action) {
                "top" -> callBack.toTop(item)
                "bottom" -> callBack.toBottom(item)
                "delete" -> {
                    callBack.delete(item)
                    selected.remove(item)
                }
            }
        }
    }

    private var dragStartPosition = RecyclerView.NO_POSITION
    private var draggedKey: Long? = null

    override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
        val source = getItem(srcPosition) ?: return false
        if (getItem(targetPosition) == null) return false
        if (dragStartPosition == RecyclerView.NO_POSITION) {
            dragStartPosition = srcPosition
            draggedKey = source.id
        } else if (source.id != draggedKey) {
            return false
        }
        swapItem(srcPosition, targetPosition)
        return true
    }

    override fun onClearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        val start = dragStartPosition
        val key = draggedKey
        dragStartPosition = RecyclerView.NO_POSITION
        draggedKey = null
        val end = viewHolder.bindingAdapterPosition
        if (start == RecyclerView.NO_POSITION || end == RecyclerView.NO_POSITION || start == end) return
        val moved = getItem(end)?.takeIf { it.id == key } ?: return
        val after = end > start
        val target = getItem(if (after) end - 1 else end + 1) ?: return
        callBack.move(moved.id, target.id, after)
    }

    val dragSelectCallback: DragSelectTouchHelper.Callback =
        object : DragSelectTouchHelper.AdvanceCallback<ReplaceRule>(Mode.ToggleAndReverse) {
            override fun currentSelectedId(): MutableSet<ReplaceRule> {
                return selected
            }

            override fun getItemId(position: Int): ReplaceRule {
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
        fun update(vararg rule: ReplaceRule)
        fun delete(rule: ReplaceRule)
        fun edit(rule: ReplaceRule)
        fun toTop(rule: ReplaceRule)
        fun toBottom(rule: ReplaceRule)
        fun move(ruleId: Long, targetId: Long, after: Boolean)
        fun upCountView()
    }
}
