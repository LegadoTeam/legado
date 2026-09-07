package io.legado.app.ui.association

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemSourceImportBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.gone
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import splitties.views.onClick

class ImportHighlightRuleDialog() : BaseDialogFragment(R.layout.dialog_recycler_view) {

    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString(ARG_SOURCE, source)
            putBoolean(ARG_FINISH_ON_DISMISS, finishOnDismiss)
        }
    }

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val viewModel by viewModels<ImportHighlightRuleViewModel>()
    private val adapter by lazy { RuleAdapter(requireContext()) }
    private var waitDialog: WaitDialog? = null

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (arguments?.getBoolean(ARG_FINISH_ON_DISMISS) == true) activity?.finish()
    }

    override fun onDestroyView() {
        waitDialog?.dismiss()
        waitDialog = null
        super.onDestroyView()
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.setTitle(R.string.highlight_rule)
        binding.rotateLoading.visible()
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.tvCancel.visible()
        binding.tvCancel.setOnClickListener { dismissAllowingStateLoss() }
        binding.tvOk.visible()
        binding.tvOk.isEnabled = false
        binding.tvOk.setOnClickListener {
            viewModel.importSelected()
        }
        viewModel.importingLiveData.observe(viewLifecycleOwner) { importing ->
            if (importing) {
                if (waitDialog == null) {
                    waitDialog = WaitDialog(requireContext()).also { it.show() }
                }
            } else {
                waitDialog?.dismiss()
                waitDialog = null
            }
            upSelectText()
        }
        viewModel.importSuccessLiveData.observe(viewLifecycleOwner) { success ->
            if (success) dismissAllowingStateLoss()
        }
        binding.tvFooterLeft.visible()
        binding.tvFooterLeft.isEnabled = false
        binding.tvFooterLeft.setOnClickListener {
            val select = !viewModel.isSelectAll
            viewModel.selectStatus.indices.forEach { viewModel.selectStatus[it] = select }
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
                adapter.setItems(viewModel.items)
                binding.tvFooterLeft.isEnabled = true
                upSelectText()
            } else {
                binding.tvMsg.apply {
                    setText(R.string.wrong_format)
                    visible()
                }
            }
        }
        val source = arguments?.getString(ARG_SOURCE)
        if (source.isNullOrEmpty()) {
            dismiss()
        } else {
            viewModel.load(source)
        }
    }

    private fun upSelectText() {
        binding.tvOk.isEnabled =
            viewModel.selectCount > 0 && viewModel.importingLiveData.value != true
        binding.tvFooterLeft.text = getString(
            if (viewModel.isSelectAll) R.string.select_cancel_count else R.string.select_all_count,
            viewModel.selectCount,
            viewModel.items.size
        )
    }

    private inner class RuleAdapter(context: Context) :
        RecyclerAdapter<HighlightRuleImportItem, ItemSourceImportBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemSourceImportBinding =
            ItemSourceImportBinding.inflate(inflater, parent, false)

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemSourceImportBinding,
            item: HighlightRuleImportItem,
            payloads: MutableList<Any>
        ) = binding.run {
            cbSourceName.isChecked = viewModel.selectStatus.getOrElse(holder.layoutPosition) { false }
            cbSourceName.text = item.rule.getDisplayName()
            tvSourceState.setText(
                when (item.status) {
                    HighlightRuleImportStatus.NEW -> R.string.import_status_new
                    HighlightRuleImportStatus.UPDATE -> R.string.import_status_update
                    HighlightRuleImportStatus.EXISTING -> R.string.import_status_exist
                }
            )
            tvOpen.gone()
        }

        override fun registerListener(
            holder: ItemViewHolder,
            binding: ItemSourceImportBinding
        ) = binding.run {
            cbSourceName.setOnUserCheckedChangeListener { isChecked ->
                holder.layoutPosition.takeIf { it in viewModel.selectStatus.indices }?.let {
                    viewModel.selectStatus[it] = isChecked
                    upSelectText()
                }
            }
            root.onClick {
                cbSourceName.performClick()
            }
        }
    }

    private companion object {
        const val ARG_SOURCE = "source"
        const val ARG_FINISH_ON_DISMISS = "finishOnDismiss"
    }
}

internal fun AppCompatActivity.showImportHighlightRuleDialog(
    source: String,
    finishOnDismiss: Boolean = false
) {
    val tag = ImportHighlightRuleDialog::class.simpleName
    if (supportFragmentManager.findFragmentByTag(tag) == null) {
        ImportHighlightRuleDialog(source, finishOnDismiss)
            .show(supportFragmentManager, tag)
    }
}
