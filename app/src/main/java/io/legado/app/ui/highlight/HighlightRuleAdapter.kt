package io.legado.app.ui.highlight

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.HighlightRule
import io.legado.app.databinding.ItemHighlightRuleBinding
import io.legado.app.ui.widget.popupActionMenu
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.ui.widget.recycler.ItemTouchCallback

class HighlightRuleAdapter(context: Context, private val callBack: CallBack) :
    RecyclerAdapter<HighlightRule, ItemHighlightRuleBinding>(context),
    ItemTouchCallback.Callback {

    private val selected = linkedSetOf<String>()

    val selection: List<HighlightRule>
        get() = getItems().filter { it.uuid in selected }

    val diffItemCallBack = object : DiffUtil.ItemCallback<HighlightRule>() {
        override fun areItemsTheSame(oldItem: HighlightRule, newItem: HighlightRule) =
            oldItem.uuid == newItem.uuid

        override fun areContentsTheSame(oldItem: HighlightRule, newItem: HighlightRule) =
            oldItem.getDisplayName() == newItem.getDisplayName() &&
                oldItem.isEnabled == newItem.isEnabled

        override fun getChangePayload(oldItem: HighlightRule, newItem: HighlightRule): Any? {
            return Bundle().apply {
                if (oldItem.getDisplayName() != newItem.getDisplayName()) {
                    putBoolean(PAYLOAD_NAME, true)
                }
                if (oldItem.isEnabled != newItem.isEnabled) {
                    putBoolean(PAYLOAD_ENABLED, newItem.isEnabled)
                }
            }.takeUnless { it.isEmpty }
        }
    }

    override fun getViewBinding(parent: ViewGroup): ItemHighlightRuleBinding =
        ItemHighlightRuleBinding.inflate(inflater, parent, false)

    fun selectAll() {
        selected.addAll(getItems().map(HighlightRule::uuid))
        notifySelectionChanged()
    }

    fun revertSelection() {
        getItems().forEach { rule ->
            if (!selected.add(rule.uuid)) selected.remove(rule.uuid)
        }
        notifySelectionChanged()
    }

    override fun onCurrentListChanged() {
        selected.retainAll(getItems().mapTo(hashSetOf(), HighlightRule::uuid))
        callBack.upCountView()
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemHighlightRuleBinding,
        item: HighlightRule,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            binding.cbName.text = item.getDisplayName()
            binding.cbName.isChecked = item.uuid in selected
            binding.swtEnabled.isChecked = item.isEnabled
            return
        }
        payloads.filterIsInstance<Bundle>().forEach { payload ->
            if (payload.containsKey(PAYLOAD_SELECTED)) {
                binding.cbName.isChecked = item.uuid in selected
            }
            if (payload.getBoolean(PAYLOAD_NAME)) {
                binding.cbName.text = item.getDisplayName()
            }
            if (payload.containsKey(PAYLOAD_ENABLED)) {
                binding.swtEnabled.isChecked = payload.getBoolean(PAYLOAD_ENABLED)
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemHighlightRuleBinding) {
        binding.root.setOnClickListener {
            binding.cbName.performClick()
        }
        binding.cbName.setOnClickListener {
            getItem(holder.layoutPosition)?.let { rule ->
                if (binding.cbName.isChecked) selected.add(rule.uuid)
                else selected.remove(rule.uuid)
                callBack.upCountView()
            }
        }
        binding.swtEnabled.setOnUserCheckedChangeListener { isChecked ->
            getItem(holder.layoutPosition)?.let { rule ->
                rule.isEnabled = isChecked
                callBack.update(rule)
            }
        }
        binding.ivEdit.setOnClickListener {
            getItem(holder.layoutPosition)?.let(callBack::edit)
        }
        binding.ivMenuMore.setOnClickListener {
            showMenu(binding.ivMenuMore, holder.layoutPosition)
        }
    }

    private fun showMenu(anchor: View, position: Int) {
        val rule = getItem(position) ?: return
        popupActionMenu(context) {
            item(context.getString(R.string.to_top), ACTION_TOP)
            item(context.getString(R.string.to_bottom), ACTION_BOTTOM)
            item(context.getString(R.string.delete), ACTION_DELETE)
            danger(ACTION_DELETE)
        }.show(anchor) { action ->
            when (action) {
                ACTION_TOP -> callBack.toTop(rule)
                ACTION_BOTTOM -> callBack.toBottom(rule)
                ACTION_DELETE -> callBack.delete(rule)
            }
        }
    }

    private val movedItems = linkedSetOf<HighlightRule>()

    override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
        val srcItem = getItem(srcPosition) ?: return false
        val targetItem = getItem(targetPosition) ?: return false
        if (srcItem.order == targetItem.order) {
            getItems().forEachIndexed { index, rule ->
                rule.order = index
                movedItems.add(rule)
            }
        }
        val srcOrder = srcItem.order
        srcItem.order = targetItem.order
        targetItem.order = srcOrder
        movedItems.add(srcItem)
        movedItems.add(targetItem)
        swapItem(srcPosition, targetPosition)
        return true
    }

    override fun onClearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        if (movedItems.isNotEmpty()) {
            callBack.update(*movedItems.toTypedArray())
            movedItems.clear()
        }
    }

    val dragSelectCallback: DragSelectTouchHelper.Callback =
        object : DragSelectTouchHelper.AdvanceCallback<String>(Mode.ToggleAndReverse) {
            override fun currentSelectedId(): MutableSet<String> = selected

            override fun getItemId(position: Int): String = getItem(position)!!.uuid

            override fun updateSelectState(position: Int, isSelected: Boolean): Boolean {
                val rule = getItem(position) ?: return false
                if (isSelected) selected.add(rule.uuid) else selected.remove(rule.uuid)
                notifyItemChanged(position, selectionPayload())
                callBack.upCountView()
                return true
            }
        }

    private fun notifySelectionChanged() {
        notifyItemRangeChanged(0, itemCount, selectionPayload())
        callBack.upCountView()
    }

    private fun selectionPayload() = Bundle().apply {
        putBoolean(PAYLOAD_SELECTED, true)
    }

    interface CallBack {
        fun update(vararg rule: HighlightRule)
        fun delete(rule: HighlightRule)
        fun edit(rule: HighlightRule)
        fun toTop(rule: HighlightRule)
        fun toBottom(rule: HighlightRule)
        fun upCountView()
    }

    private companion object {
        const val PAYLOAD_NAME = "name"
        const val PAYLOAD_ENABLED = "enabled"
        const val PAYLOAD_SELECTED = "selected"
        const val ACTION_TOP = "top"
        const val ACTION_BOTTOM = "bottom"
        const val ACTION_DELETE = "delete"
    }
}
