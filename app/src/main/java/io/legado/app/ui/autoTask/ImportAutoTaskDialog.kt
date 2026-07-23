package io.legado.app.ui.autoTask

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemSourceImportBinding
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.gone
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import splitties.views.onClick

class ImportAutoTaskDialog() : BaseDialogFragment(R.layout.dialog_recycler_view),
    CodeDialog.Callback {

    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("source", source)
            putBoolean("finishOnDismiss", finishOnDismiss)
        }
    }

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val viewModel by viewModels<ImportAutoTaskViewModel>()
    private val adapter by lazy { SourcesAdapter(requireContext()) }
    private var waitDialog: WaitDialog? = null

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (arguments?.getBoolean("finishOnDismiss") == true) activity?.finish()
    }

    override fun onDestroyView() {
        waitDialog?.dismiss()
        waitDialog = null
        super.onDestroyView()
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setTitle(R.string.import_auto_task)
        binding.rotateLoading.visible()
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.tvCancel.visible()
        binding.tvCancel.setOnClickListener { dismissAllowingStateLoss() }
        binding.tvOk.visible()
        binding.tvOk.isEnabled = false
        binding.tvOk.setOnClickListener {
            waitDialog = WaitDialog(requireContext()).also { it.show() }
            viewModel.importSelect {
                waitDialog?.dismiss()
                waitDialog = null
                dismissAllowingStateLoss()
            }
        }
        binding.tvFooterLeft.visible()
        binding.tvFooterLeft.isEnabled = false
        binding.tvFooterLeft.setOnClickListener {
            val selectAll = viewModel.isSelectAll
            viewModel.selectStatus.indices.forEach { index ->
                viewModel.selectStatus[index] = !selectAll
            }
            adapter.notifyDataSetChanged()
            upSelectText()
        }
        viewModel.errorLiveData.observe(viewLifecycleOwner) {
            waitDialog?.dismiss()
            waitDialog = null
            binding.rotateLoading.gone()
            binding.tvMsg.apply {
                text = it
                visible()
            }
        }
        viewModel.successLiveData.observe(viewLifecycleOwner) {
            binding.rotateLoading.gone()
            if (it > 0) {
                adapter.setItems(viewModel.allTasks)
                binding.tvOk.isEnabled = true
                binding.tvFooterLeft.isEnabled = true
                upSelectText()
            } else {
                binding.tvMsg.apply {
                    setText(R.string.wrong_format)
                    visible()
                }
            }
        }
        val source = arguments?.getString("source")
        if (source.isNullOrEmpty()) {
            dismiss()
            return
        }
        viewModel.importSource(source)
    }

    private fun upSelectText() {
        binding.tvFooterLeft.text = getString(
            if (viewModel.isSelectAll) R.string.select_cancel_count else R.string.select_all_count,
            viewModel.selectCount,
            viewModel.allTasks.size
        )
    }

    inner class SourcesAdapter(context: Context) :
        RecyclerAdapter<AutoTaskRule, ItemSourceImportBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemSourceImportBinding {
            return ItemSourceImportBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemSourceImportBinding,
            item: AutoTaskRule,
            payloads: MutableList<Any>
        ) {
            binding.cbSourceName.isChecked = viewModel.selectStatus[holder.layoutPosition]
            binding.cbSourceName.text = item.name.ifBlank { item.id }
            val localTask = viewModel.checkTasks[holder.layoutPosition]
            binding.tvSourceState.text = when {
                localTask == null -> getString(R.string.import_status_new)
                !sameAutoTaskForImport(item, localTask) -> getString(R.string.import_status_update)
                else -> getString(R.string.import_status_exist)
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemSourceImportBinding) {
            binding.cbSourceName.setOnCheckedChangeListener { buttonView, isChecked ->
                if (buttonView.isPressed) {
                    viewModel.selectStatus[holder.layoutPosition] = isChecked
                    upSelectText()
                }
            }
            binding.root.onClick {
                binding.cbSourceName.isChecked = !binding.cbSourceName.isChecked
                viewModel.selectStatus[holder.layoutPosition] = binding.cbSourceName.isChecked
                upSelectText()
            }
            binding.tvOpen.setOnClickListener {
                val task = viewModel.allTasks.getOrNull(holder.layoutPosition) ?: return@setOnClickListener
                showDialogFragment(
                    CodeDialog(
                        GSON.toJson(task),
                        disableEdit = false,
                        requestId = holder.layoutPosition.toString()
                    )
                )
            }
        }
    }

    override fun onCodeSave(code: String, requestId: String?) {
        requestId?.toIntOrNull()?.let { index ->
            GSON.fromJsonObject<AutoTaskRule>(code).getOrNull()?.let { task ->
                viewModel.updateTaskAt(index, task)?.let { validatedTask ->
                    adapter.setItem(index, validatedTask)
                    binding.tvMsg.gone()
                    upSelectText()
                }
            }
        }
    }
}
