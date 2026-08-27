package io.legado.app.ui.autoTask

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppConst
import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.databinding.ItemAutoTaskBinding
import io.legado.app.model.AutoTask
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.widget.popupActionMenu
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.utils.startActivity

class AutoTaskAdapter(context: Context, private val callback: Callback) :
    RecyclerAdapter<AutoTaskRule, ItemAutoTaskBinding>(context) {

    private val selectedIds = linkedSetOf<String>()

    val selection: List<AutoTaskRule>
        get() = getItems().filter { it.id in selectedIds }

    val selectionCount: Int
        get() = selection.size

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
        cbTask.text = item.name
        cbTask.isChecked = item.id in selectedIds
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
        binding.cbTask.setOnUserCheckedChangeListener { selected ->
            getItem(holder.bindingAdapterPosition)?.let { task ->
                if (selected) selectedIds.add(task.id) else selectedIds.remove(task.id)
                callback.upCountView()
            }
        }
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

    override fun onCurrentListChanged() {
        callback.upCountView()
    }

    fun retainExistingSelections(tasks: List<AutoTaskRule>) {
        selectedIds.retainAll(tasks.mapTo(hashSetOf()) { it.id })
    }

    fun selectAll() {
        getItems().forEach { selectedIds.add(it.id) }
        notifyItemRangeChanged(0, itemCount)
        callback.upCountView()
    }

    fun revertSelection() {
        getItems().forEach { task ->
            if (!selectedIds.remove(task.id)) selectedIds.add(task.id)
        }
        notifyItemRangeChanged(0, itemCount)
        callback.upCountView()
    }

    val dragSelectCallback: DragSelectTouchHelper.Callback =
        object : DragSelectTouchHelper.AdvanceCallback<String>(
            DragSelectTouchHelper.AdvanceCallback.Mode.ToggleAndReverse
        ) {
            override fun currentSelectedId(): MutableSet<String> = selectedIds

            override fun getItemId(position: Int): String = getItem(position)!!.id

            override fun updateSelectState(position: Int, isSelected: Boolean): Boolean {
                val task = getItem(position) ?: return false
                if (isSelected) selectedIds.add(task.id) else selectedIds.remove(task.id)
                notifyItemChanged(position)
                callback.upCountView()
                return true
            }
        }

    private fun showMenu(anchor: View, position: Int) {
        val task = getItem(position) ?: return
        popupActionMenu(context) {
            item(context.getString(R.string.login), "login", AutoTask.buildSource(task).hasLogin())
            item(context.getString(R.string.auto_task_log), "log")
            item(context.getString(R.string.auto_task_move_up), "moveUp")
            item(context.getString(R.string.auto_task_move_down), "moveDown")
            item(context.getString(R.string.delete), "delete")
            danger("delete")
        }.show(anchor) { action ->
            when (action) {
                "login" -> context.startActivity<SourceLoginActivity> {
                    putExtra("type", "autoTask")
                    putExtra("key", task.id)
                }

                "log" -> callback.showLog(task)
                "moveUp" -> callback.move(task, -1)
                "moveDown" -> callback.move(task, 1)
                "delete" -> callback.delete(task)
            }
        }
    }

    interface Callback {
        fun edit(task: AutoTaskRule)
        fun debug(task: AutoTaskRule)
        fun toggle(task: AutoTaskRule, enabled: Boolean)
        fun move(task: AutoTaskRule, offset: Int)
        fun showLog(task: AutoTaskRule)
        fun delete(task: AutoTaskRule)
        fun upCountView()
    }
}
