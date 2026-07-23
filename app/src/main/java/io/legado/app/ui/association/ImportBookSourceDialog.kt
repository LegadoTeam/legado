package io.legado.app.ui.association

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.DialogCustomGroupBinding
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemSourceImportBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.gone
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import splitties.views.onClick


/**
 * 导入书源弹出窗口
 */
class ImportBookSourceDialog() : BaseDialogFragment(R.layout.dialog_recycler_view),
    Toolbar.OnMenuItemClickListener,
    CodeDialog.Callback {

    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("source", source)
            putBoolean("finishOnDismiss", finishOnDismiss)
        }
    }

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val viewModel by viewModels<ImportBookSourceViewModel>()
    private val adapter by lazy { SourcesAdapter(requireContext()) }
    private var sourceListReady = false

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (arguments?.getBoolean("finishOnDismiss") == true) {
            activity?.finish()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.setTitle(R.string.import_book_source)
        binding.rotateLoading.visible()
        initMenu()
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.tvCancel.visible()
        binding.tvCancel.setOnClickListener {
            dismissAllowingStateLoss()
        }
        binding.tvOk.visible()
        binding.tvOk.isEnabled = false
        binding.tvOk.setOnClickListener {
            if (viewModel.sourceUpdatePending.value == true) return@setOnClickListener
            val waitDialog = WaitDialog(requireContext())
            waitDialog.show()
            viewModel.importSelect {
                waitDialog.dismiss()
                dismissAllowingStateLoss()
            }
        }
        binding.tvFooterLeft.visible()
        binding.tvFooterLeft.isEnabled = false
        binding.tvFooterLeft.setOnClickListener {
            val selectAll = viewModel.isSelectAll
            viewModel.selectStatus.forEachIndexed { index, b ->
                if (b != !selectAll) {
                    viewModel.setSelection(index, !selectAll)
                }
            }
            adapter.notifyDataSetChanged()
            upSelectText()
        }
        viewModel.errorLiveData.observe(viewLifecycleOwner) {
            binding.rotateLoading.gone()
            binding.ivEmpty.visible()
            binding.tvMsg.apply {
                text = it
                visible()
            }
        }
        viewModel.successLiveData.observe(viewLifecycleOwner) {
            binding.rotateLoading.gone()
            if (it > 0) {
                sourceListReady = true
                binding.ivEmpty.gone()
                binding.tvMsg.gone()
                adapter.setItems(viewModel.allSources)
                upSelectText()
                updateInteractionState()
            } else {
                binding.ivEmpty.visible()
                binding.tvMsg.apply {
                    setText(R.string.wrong_format)
                    visible()
                }
            }
        }
        viewModel.sourceUpdatePending.observe(viewLifecycleOwner) {
            updateInteractionState()
        }
        val source = arguments?.getString("source")
        if (source.isNullOrEmpty()) {
            dismiss()
            return
        }
        viewModel.importSource(source)
    }

    private fun upSelectText() {
        if (viewModel.isSelectAll) {
            binding.tvFooterLeft.text = getString(
                R.string.select_cancel_count,
                viewModel.selectCount,
                viewModel.allSources.size
            )
        } else {
            binding.tvFooterLeft.text = getString(
                R.string.select_all_count,
                viewModel.selectCount,
                viewModel.allSources.size
            )
        }
    }

    private fun initMenu() {
        binding.toolBar.setOnMenuItemClickListener(this)
        binding.toolBar.inflateMenu(R.menu.import_source)
        binding.toolBar.menu.apply {
            findItem(R.id.menu_keep_original_name)
                ?.isChecked = AppConfig.importKeepName
            findItem(R.id.menu_keep_group)
                ?.isChecked = AppConfig.importKeepGroup
            findItem(R.id.menu_keep_enable)
                ?.isChecked = AppConfig.importKeepEnable
            findItem(R.id.menu_show_comment)
                ?.isChecked = AppConfig.importShowComment
        }
    }

    @SuppressLint("InflateParams", "NotifyDataSetChanged")
    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_new_group -> alertCustomGroup(item)
            R.id.menu_select_new_source -> {
                val selectAllNew = viewModel.isSelectAllNew
                viewModel.newSourceStatus.forEachIndexed { index, b ->
                    if (b) {
                        viewModel.setSelection(index, !selectAllNew)
                    }
                }
                adapter.notifyDataSetChanged()
                upSelectText()
            }

            R.id.menu_select_update_source -> {
                val selectAllUpdate = viewModel.isSelectAllUpdate
                viewModel.updateSourceStatus.forEachIndexed { index, b ->
                    if (b) {
                        viewModel.setSelection(index, !selectAllUpdate)
                    }
                }
                adapter.notifyDataSetChanged()
                upSelectText()
            }

            R.id.menu_keep_original_name -> {
                item.isChecked = !item.isChecked
                putPrefBoolean(PreferKey.importKeepName, item.isChecked)
            }

            R.id.menu_keep_group -> {
                item.isChecked = !item.isChecked
                putPrefBoolean(PreferKey.importKeepGroup, item.isChecked)
            }

            R.id.menu_keep_enable -> {
                item.isChecked = !item.isChecked
                AppConfig.importKeepEnable = item.isChecked
            }

            R.id.menu_show_comment -> {
                item.isChecked = !item.isChecked
                AppConfig.importShowComment = item.isChecked
                adapter.notifyDataSetChanged()
            }
        }
        return false
    }

    private fun alertCustomGroup(item: MenuItem) {
        alert(R.string.diy_edit_source_group) {
            val alertBinding = DialogCustomGroupBinding.inflate(layoutInflater).apply {
                val groups = appDb.bookSourceDao.allGroups()
                textInputLayout.setHint(R.string.group_name)
                editView.setFilterValues(groups.toList())
                editView.dropDownHeight = 180.dpToPx()
            }
            customView {
                alertBinding.root
            }
            okButton {
                viewModel.isAddGroup = alertBinding.swAddGroup.isChecked
                viewModel.groupName = alertBinding.editView.text?.toString()
                if (viewModel.groupName.isNullOrBlank()) {
                    item.title = getString(R.string.diy_source_group)
                } else {
                    val group = getString(R.string.diy_edit_source_group_title, viewModel.groupName)
                    if (viewModel.isAddGroup) {
                        item.title = "+$group"
                    } else {
                        item.title = group
                    }
                }
            }
            cancelButton()
        }
    }

    private fun updateInteractionState() {
        val sourceUpdatePending = viewModel.sourceUpdatePending.value == true
        val importEnabled = sourceListReady && !sourceUpdatePending
        binding.tvOk.isEnabled = importEnabled
        binding.tvFooterLeft.isEnabled = importEnabled
        binding.tvCancel.isEnabled = !sourceUpdatePending
        isCancelable = !sourceUpdatePending
    }

    override fun onCodeSave(code: String, requestId: String?) {
        if (viewModel.sourceUpdatePending.value == true) return
        val index = requestId?.toIntOrNull() ?: return
        if (index !in viewModel.allSources.indices) return
        val source = GSON.fromJsonObject<BookSource>(code).getOrNull() ?: return
        viewModel.updateSource(index, source)
    }

    inner class SourcesAdapter(context: Context) :
        RecyclerAdapter<BookSource, ItemSourceImportBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemSourceImportBinding {
            return ItemSourceImportBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemSourceImportBinding,
            item: BookSource,
            payloads: MutableList<Any>
        ) {
            binding.apply {
                cbSourceName.isChecked = viewModel.selectStatus[holder.layoutPosition]
                cbSourceName.text = item.bookSourceName
                if (AppConfig.importShowComment) {
                    item.bookSourceComment?.takeIf{ it.isNotBlank() }?.let {
                        showComment.text = it
                        showComment.visible()
                        showComment.setOnClickListener {
                            if (showComment.maxLines == 3) {
                                showComment.maxLines = 39
                            } else {
                                showComment.maxLines = 3
                            }
                        }
                    } ?: run {
                        showComment.gone()
                    }
                } else {
                    showComment.gone()
                }
                tvSourceState.text = when {
                    viewModel.newSourceStatus[holder.layoutPosition] -> "新增"
                    viewModel.updateSourceStatus[holder.layoutPosition] -> "更新"
                    else -> "已有"
                }
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemSourceImportBinding) {
            binding.apply {
                cbSourceName.setOnUserCheckedChangeListener { isChecked ->
                    viewModel.setSelection(holder.layoutPosition, isChecked)
                    upSelectText()
                }
                root.onClick {
                    cbSourceName.isChecked = !cbSourceName.isChecked
                    viewModel.setSelection(holder.layoutPosition, cbSourceName.isChecked)
                    upSelectText()
                }
                tvOpen.setOnClickListener {
                    if (viewModel.sourceUpdatePending.value == true) {
                        return@setOnClickListener
                    }
                    val source = viewModel.allSources[holder.layoutPosition]
                    showDialogFragment(
                        CodeDialog(
                            GSON.toJson(source),
                            disableEdit = false,
                            requestId = holder.layoutPosition.toString()
                        )
                    )
                }
            }
        }

    }

}
