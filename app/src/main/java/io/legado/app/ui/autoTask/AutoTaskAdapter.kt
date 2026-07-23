package io.legado.app.ui.autoTask

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppConst
import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.databinding.ItemAutoTaskBinding

class AutoTaskAdapter(context: Context, private val callback: Callback) :
    RecyclerAdapter<AutoTaskRule, ItemAutoTaskBinding>(context) {

    val diffCallback = object : DiffUtil.ItemCallback<AutoTaskRule>() {
        override fun areItemsTheSame(oldItem: AutoTaskRule, newItem: AutoTaskRule) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: AutoTaskRule, newItem: AutoTaskRule) =
            oldItem == newItem
    }

    override fun getViewBinding(parent: ViewGroup): ItemAutoTaskBinding {
        return ItemAutoTaskBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemAutoTaskBinding,
        item: AutoTaskRule,
        payloads: MutableList<Any>
    ) = binding.run {
        tvName.text = item.name
        tvSummary.text = buildString {
            append(item.cron.orEmpty())
            when {
                !item.lastError.isNullOrBlank() -> append(" | ").append(item.lastError)
                item.lastRunAt > 0L -> append(" | ").append(AppConst.dateFormat.format(item.lastRunAt))
            }
        }
        swtEnabled.isChecked = item.enable
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemAutoTaskBinding) {
        binding.root.setOnClickListener {
            getItem(holder.bindingAdapterPosition)?.let(callback::edit)
        }
        binding.swtEnabled.setOnUserCheckedChangeListener { enabled ->
            getItem(holder.bindingAdapterPosition)?.let { callback.toggle(it, enabled) }
        }
        binding.ivDebug.setOnClickListener {
            getItem(holder.bindingAdapterPosition)?.let(callback::debug)
        }
        binding.ivEdit.setOnClickListener {
            getItem(holder.bindingAdapterPosition)?.let(callback::edit)
        }
        binding.ivMenuMore.setOnClickListener {
            showMenu(it, holder.bindingAdapterPosition)
        }
    }

    private fun showMenu(anchor: View, position: Int) {
        val task = getItem(position) ?: return
        PopupMenu(context, anchor).apply {
            inflate(R.menu.auto_task_item)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_log -> callback.showLog(task)
                    R.id.menu_move_up -> callback.move(task, -1)
                    R.id.menu_move_down -> callback.move(task, 1)
                    R.id.menu_del -> callback.delete(task)
                }
                true
            }
            show()
        }
    }

    interface Callback {
        fun edit(task: AutoTaskRule)
        fun debug(task: AutoTaskRule)
        fun toggle(task: AutoTaskRule, enabled: Boolean)
        fun move(task: AutoTaskRule, offset: Int)
        fun showLog(task: AutoTaskRule)
        fun delete(task: AutoTaskRule)
    }
}
