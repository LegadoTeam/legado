package io.legado.app.ui.association

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.import.local.ImportBookAdapter
import io.legado.app.utils.FileDoc
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible

/** Reuse the local importer rows, including their supported-format label and selection. */
class ImportLocalBookDialog : BaseDialogFragment(R.layout.dialog_recycler_view), ImportBookAdapter.CallBack {
    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val viewModel by activityViewModels<FileAssociationViewModel>()
    private val adapter by lazy { ImportBookAdapter(requireContext(), this) }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.setTitle(R.string.local_book)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        val items = viewModel.localBookBatch.value.orEmpty()
        adapter.selected.addAll(items.filter { it.file.uri in viewModel.selectedLocalBooks })
        adapter.setItems(items)
        binding.tvCancel.visible()
        binding.tvCancel.setOnClickListener { dismiss() }
        binding.tvOk.visible()
        binding.tvOk.setText(R.string.add_to_bookshelf)
        binding.tvOk.setOnClickListener { viewModel.confirmLocalBooks() }
        binding.tvFooterLeft.visible()
        binding.tvFooterLeft.setOnClickListener { adapter.selectAll(adapter.selected.size != items.size) }
        viewModel.importingLocalBooks.observe(viewLifecycleOwner) { importing ->
            isCancelable = !importing
            binding.recyclerView.isEnabled = !importing
            binding.tvCancel.isEnabled = !importing
            binding.rotateLoading.visibility = if (importing) View.VISIBLE else View.GONE
            upCountView()
        }
        viewModel.localBookDestination.observe(viewLifecycleOwner) { upCountView() }
        upCountView()
    }

    override fun upCountView() {
        viewModel.updateLocalSelection(adapter.selected.map { it.file.uri })
        val items = viewModel.localBookBatch.value.orEmpty()
        val busy = viewModel.importingLocalBooks.value == true || viewModel.localBookDestination.value == true
        binding.tvOk.isEnabled = adapter.selected.isNotEmpty() && !busy
        binding.tvFooterLeft.isEnabled = !busy
        binding.tvFooterLeft.text = getString(
            if (adapter.selected.size == items.size) R.string.select_cancel_count else R.string.select_all_count,
            adapter.selected.size, items.size)
    }

    override fun nextDoc(fileDoc: FileDoc) = Unit
    override fun startRead(fileDoc: FileDoc) = Unit

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        activity?.finish()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (activity?.isChangingConfigurations != true) activity?.finish()
    }
}
