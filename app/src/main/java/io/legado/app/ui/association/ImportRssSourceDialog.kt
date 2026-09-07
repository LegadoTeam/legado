package io.legado.app.ui.association

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.databinding.DialogCustomGroupBinding
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemSourceImportBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import splitties.views.onClick

/**
 * 导入rss源弹出窗口
 */
class ImportRssSourceDialog() : BaseDialogFragment(R.layout.dialog_recycler_view),
    Toolbar.OnMenuItemClickListener,
    CodeDialog.Callback {

    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("source", source)
            putBoolean("finishOnDismiss", finishOnDismiss)
        }
    }

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val viewModel by viewModels<ImportRssSourceViewModel>()
    private val adapter by lazy { SourcesAdapter(requireContext()) }
    private var sourceListReady = false
    private var pendingReplacementRefresh: Pair<String, String?>? = null
    private val replaceRuleActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK) {
                openCodeDialog()?.let { dialog ->
                    pendingReplacementRefresh = dialog.currentOriginalCode() to dialog.requestId
                    dialog.setReplaceRuleRefreshPending(true)
                    if (!startPendingReplacementRefresh() && pendingReplacementRefresh == null) {
                        syncOpenCodeDialog()
                    }
                }
            }
        }

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
        binding.toolBar.setTitle(R.string.import_rss_source)
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
            binding.tvMsg.apply {
                text = it
                visible()
            }
        }
        viewModel.successLiveData.observe(viewLifecycleOwner) {
            binding.rotateLoading.gone()
            if (it > 0) {
                sourceListReady = true
                adapter.setItems(viewModel.allSources)
                upSelectText()
                updateInteractionState()
                if (viewModel.sourceUpdatePending.value != true &&
                    !startPendingReplacementRefresh() &&
                    pendingReplacementRefresh == null
                ) {
                    syncOpenCodeDialog()
                }
            } else {
                binding.tvMsg.apply {
                    setText(R.string.wrong_format)
                    visible()
                }
            }
        }
        viewModel.sourceUpdatePending.observe(viewLifecycleOwner) {
            adapter.notifyDataSetChanged()
            updateInteractionState()
            openCodeDialog()?.setReplaceRuleRefreshPending(
                it == true || pendingReplacementRefresh != null
            )
            if (it != true &&
                !startPendingReplacementRefresh() &&
                pendingReplacementRefresh == null
            ) {
                syncOpenCodeDialog()
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
            findItem(R.id.menu_replace_source)
                ?.isChecked = viewModel.useSourceReplacement
            findItem(R.id.menu_select_new_source)?.isVisible = false // 暂不支持
            findItem(R.id.menu_select_update_source)?.isVisible = false // 暂不支持
        }
    }

    @SuppressLint("InflateParams", "NotifyDataSetChanged")
    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_new_group -> alertCustomGroup(item)
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

            R.id.menu_replace_source -> {
                item.isChecked = !item.isChecked
                viewModel.setUseSourceReplacement(item.isChecked)
            }
        }
        return false
    }

    private fun alertCustomGroup(item: MenuItem) {
        alert(R.string.diy_edit_source_group) {
            val alertBinding = DialogCustomGroupBinding.inflate(layoutInflater).apply {
                val groups = appDb.rssSourceDao.allGroups()
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

    override fun onCodeSave(code: String, requestId: String?) {
        if (viewModel.sourceUpdatePending.value == true) return
        val index = requestId?.toIntOrNull() ?: return
        val source = runCatching { parseSingleRssSourceJson(code) }.getOrNull() ?: return
        viewModel.refreshSourceReplacements(index, source)
    }

    override fun onOpenReplaceRules() {
        replaceRuleActivity.launch(Intent(requireContext(), ReplaceRuleActivity::class.java))
    }

    private fun startPendingReplacementRefresh(): Boolean {
        if (!sourceListReady || viewModel.sourceUpdatePending.value == true) return false
        val (code, requestId) = pendingReplacementRefresh ?: return false
        val index = requestId?.toIntOrNull()
        if (index == null || index !in viewModel.allSources.indices) {
            pendingReplacementRefresh = null
            return false
        }
        val source = runCatching { parseSingleRssSourceJson(code) }.getOrNull()
        val started = viewModel.refreshSourceReplacements(index, source)
        if (started) pendingReplacementRefresh = null
        return started
    }

    private fun openCodeDialog(): CodeDialog? =
        childFragmentManager.findFragmentByTag(CodeDialog::class.simpleName) as? CodeDialog

    private fun syncOpenCodeDialog() {
        openCodeDialog()?.let { dialog ->
            dialog.setReplaceRuleRefreshPending(false)
            if (runCatching {
                    parseSingleRssSourceJson(dialog.currentOriginalCode())
                }.getOrNull() != null
            ) {
                dialog.refreshAlternateCode()
            } else {
                dialog.clearAlternateCode()
            }
        }
    }

    private fun updateInteractionState() {
        val sourceUpdatePending = viewModel.sourceUpdatePending.value == true
        val importEnabled = sourceListReady && !sourceUpdatePending
        binding.tvOk.isEnabled = importEnabled
        binding.tvFooterLeft.isEnabled = importEnabled
        binding.tvCancel.isEnabled = !sourceUpdatePending
        isCancelable = !sourceUpdatePending
        binding.toolBar.menu.findItem(R.id.menu_replace_source)?.apply {
            isChecked = viewModel.useSourceReplacement
            isEnabled = importEnabled
        }
    }

    override fun isReplaceRuleRefreshPending(): Boolean =
        pendingReplacementRefresh != null || viewModel.sourceUpdatePending.value == true

    override fun getCodeAlternate(requestId: String?): String? {
        val index = requestId?.toIntOrNull() ?: return null
        return viewModel.replacedSourceJson(index)
    }

    inner class SourcesAdapter(context: Context) :
        RecyclerAdapter<RssSource, ItemSourceImportBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemSourceImportBinding {
            return ItemSourceImportBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemSourceImportBinding,
            item: RssSource,
            payloads: MutableList<Any>
        ) {
            binding.apply {
                val position = holder.layoutPosition
                val canImport = viewModel.canImportSource(position)
                val interactionEnabled = viewModel.sourceUpdatePending.value != true
                val replacementError = viewModel.sourceReplacementError(position)
                    ?.takeIf { viewModel.useSourceReplacement }
                cbSourceName.isChecked = viewModel.selectStatus[position]
                cbSourceName.isEnabled = canImport && interactionEnabled
                cbSourceName.text = item.sourceName
                val comment = replacementError?.let {
                    getString(R.string.source_replacement_error, it)
                } ?: item.sourceComment?.takeIf {
                    AppConfig.importShowComment && it.isNotBlank()
                }
                if (comment != null) {
                    showComment.text = comment
                    showComment.maxLines = 3
                    showComment.visible()
                    showComment.setOnClickListener {
                        if (showComment.maxLines == 3) {
                            showComment.maxLines = 39
                        } else {
                            showComment.maxLines = 3
                        }
                    }
                } else {
                    showComment.gone()
                }
                val localSource = viewModel.checkSources[position]
                tvSourceState.setText(
                    when {
                        replacementError != null -> R.string.import_status_error
                        localSource == null -> R.string.import_status_new
                        item.lastUpdateTime > localSource.lastUpdateTime ->
                            R.string.import_status_update

                        else -> R.string.import_status_exist
                    }
                )
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemSourceImportBinding) {
            binding.apply {
                cbSourceName.setOnUserCheckedChangeListener { isChecked ->
                    if (viewModel.sourceUpdatePending.value == true) {
                        return@setOnUserCheckedChangeListener
                    }
                    viewModel.setSelection(holder.layoutPosition, isChecked)
                    upSelectText()
                }
                root.onClick {
                    if (viewModel.sourceUpdatePending.value == true ||
                        !viewModel.canImportSource(holder.layoutPosition)
                    ) {
                        return@onClick
                    }
                    cbSourceName.isChecked = !cbSourceName.isChecked
                    viewModel.setSelection(holder.layoutPosition, cbSourceName.isChecked)
                    upSelectText()
                }
                tvOpen.setOnClickListener {
                    if (viewModel.sourceUpdatePending.value == true) {
                        return@setOnClickListener
                    }
                    val position = holder.layoutPosition
                    showDialogFragment(
                        CodeDialog(
                            viewModel.originalSourceJson(position) ?: return@setOnClickListener,
                            disableEdit = false,
                            requestId = position.toString(),
                            alternateCode = viewModel.replacedSourceJson(position),
                            showAlternate = viewModel.useSourceReplacement,
                            showReplaceRules = true,
                        )
                    )
                }
            }
        }
    }

}
