package io.legado.app.ui.highlight

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemGroupManageBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.requestInputMethod
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HighlightGroupManageDialog : BaseDialogFragment(R.layout.dialog_recycler_view) {

    private val viewModel: HighlightRuleViewModel by activityViewModels()
    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val adapter by lazy { GroupAdapter(requireContext()) }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.setBackgroundColor(backgroundColor)
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.setTitle(R.string.highlight_rule_group_manage)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        binding.recyclerView.adapter = adapter
        lifecycleScope.launch {
            appDb.highlightRuleDao.flowGroups().conflate().collect { adapter.setItems(it) }
        }
    }

    @SuppressLint("InflateParams")
    private fun editGroup(group: String) {
        alert(title = getString(R.string.group_edit)) {
            val edit = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.setHint(R.string.group_name)
                editView.setText(group)
            }
            customView { edit.root }
            okButton {
                edit.editView.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    viewModel.renameGroup(group, it)
                }
            }
            cancelButton()
        }.requestInputMethod()
    }

    private fun deleteGroup(group: String) {
        alert(title = getString(R.string.highlight_rule_group_delete)) {
            setMessage(R.string.highlight_rule_group_delete_message)
            positiveButton(R.string.delete) {
                viewModel.deleteGroup(group).onSuccess { ReadBook.upHighlightRules() }
            }
            negativeButton(R.string.move_to_group) { chooseMoveTarget(group) }
            neutralButton(android.R.string.cancel)
        }
    }

    private fun chooseMoveTarget(source: String) {
        lifecycleScope.launch {
            val values = appDb.highlightRuleDao.flowGroups().first()
                .filterNot { it == source }.map { MoveTarget("[$it]", it) } +
                MoveTarget(getString(R.string.no_group), null)
            alert(title = getString(R.string.move_to_group)) {
                items(values) { _, target, _ ->
                    viewModel.moveGroup(source, target.group)
                        .onSuccess { ReadBook.upHighlightRules() }
                }
            }
        }
    }

    private data class MoveTarget(val title: String, val group: String?) {
        override fun toString() = title
    }

    private inner class GroupAdapter(context: Context) :
        RecyclerAdapter<String, ItemGroupManageBinding>(context) {
        override fun getViewBinding(parent: ViewGroup) =
            ItemGroupManageBinding.inflate(inflater, parent, false)

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemGroupManageBinding,
            item: String,
            payloads: MutableList<Any>
        ) {
            binding.root.setBackgroundColor(context.backgroundColor)
            binding.tvGroup.text = item
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemGroupManageBinding) {
            binding.tvEdit.setOnClickListener { getItem(holder.layoutPosition)?.let(::editGroup) }
            binding.tvDel.setOnClickListener { getItem(holder.layoutPosition)?.let(::deleteGroup) }
        }
    }
}
