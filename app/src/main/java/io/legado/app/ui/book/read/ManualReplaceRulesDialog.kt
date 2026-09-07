package io.legado.app.ui.book.read

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemCheckBoxBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible

/** Selects the replacement rules used by the current book in the reader. */
class ManualReplaceRulesDialog : BaseDialogFragment(R.layout.dialog_recycler_view) {

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val viewModel by activityViewModels<ReadBookViewModel>()
    private val adapter by lazy { RulesAdapter(requireContext()) }
    private var candidates: List<ReplaceRule> = emptyList()

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val book = ReadBook.book ?: run {
            dismissAllowingStateLoss()
            return
        }
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.setTitle(R.string.manual_replace_rule)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        candidates = appDb.replaceRuleDao.findManualCandidates()
        adapter.selectedIds.addAll(book.config.manualReplaceRuleIds)
        adapter.selectedIds.retainAll(candidates.mapTo(hashSetOf(), ReplaceRule::id))
        adapter.setItems(candidates)

        binding.tvCancel.visible()
        binding.tvCancel.setOnClickListener { dismissAllowingStateLoss() }
        binding.tvOk.visible()
        binding.tvOk.setOnClickListener {
            book.config.manualReplaceRuleIds = candidates
                .filter { it.id in adapter.selectedIds }
                .map { it.id }
            ReadBook.saveRead()
            viewModel.replaceRuleChanged()
            dismissAllowingStateLoss()
        }
        binding.tvFooterLeft.visible()
        binding.tvFooterLeft.setOnClickListener {
            if (adapter.selectedIds.size == candidates.size) {
                adapter.selectedIds.clear()
            } else {
                adapter.selectedIds.addAll(candidates.map(ReplaceRule::id))
            }
            adapter.notifyDataSetChanged()
            updateFooter()
        }
        updateFooter()

        val dragSelectTouchHelper = DragSelectTouchHelper(adapter.dragSelectCallback)
            .setSlideArea(16, 50)
        dragSelectTouchHelper.attachToRecyclerView(binding.recyclerView)
        dragSelectTouchHelper.activeSlideSelect()
    }

    private fun updateFooter() {
        val selected = adapter.selectedIds.size
        val all = selected == candidates.size
        binding.tvFooterLeft.text = getString(
            if (all) R.string.select_cancel_count else R.string.select_all_count,
            selected,
            candidates.size,
        )
    }

    private inner class RulesAdapter(context: android.content.Context) :
        RecyclerAdapter<ReplaceRule, ItemCheckBoxBinding>(context) {

        val selectedIds = linkedSetOf<Long>()

        override fun getViewBinding(parent: ViewGroup): ItemCheckBoxBinding {
            return ItemCheckBoxBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemCheckBoxBinding,
            item: ReplaceRule,
            payloads: MutableList<Any>,
        ) {
            binding.checkBox.text = item.getDisplayNameGroup()
            binding.checkBox.isChecked = item.id in selectedIds
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemCheckBoxBinding) {
            binding.checkBox.setOnUserCheckedChangeListener { isChecked ->
                getItem(holder.layoutPosition)?.let { item ->
                    if (isChecked) selectedIds.add(item.id) else selectedIds.remove(item.id)
                    notifyItemChanged(holder.layoutPosition)
                    updateFooter()
                }
            }
            binding.root.setOnClickListener { binding.checkBox.performClick() }
        }

        val dragSelectCallback =
            object : DragSelectTouchHelper.AdvanceCallback<ReplaceRule>(Mode.ToggleAndReverse) {
                override fun currentSelectedId(): MutableSet<ReplaceRule> {
                    return getItems().filter { it.id in selectedIds }.toMutableSet()
                }

                override fun getItemId(position: Int): ReplaceRule {
                    return getItem(position)!!
                }

                override fun updateSelectState(position: Int, isSelected: Boolean): Boolean {
                    val item = getItem(position) ?: return false
                    if (isSelected) selectedIds.add(item.id) else selectedIds.remove(item.id)
                    notifyItemChanged(position)
                    updateFooter()
                    return true
                }
            }
    }
}
