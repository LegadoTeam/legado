package io.legado.app.ui.association

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
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
import io.legado.app.ui.book.read.EffectiveReplacesDialog
import io.legado.app.ui.book.read.ManualReplaceRulesDialog
import io.legado.app.utils.toastOnUi
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
    CodeDialog.Callback, ManualReplaceRulesDialog.Callback, EffectiveReplacesDialog.Callback {

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
                } ?: viewModel.refreshSourceReplacements()
            }
        }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.setTitle(R.string.import_rss_source)
        binding.rotateLoading.visible()
        initMenu()
        binding.sourceImportSearch.apply {
            visible()
            setQuery(viewModel.searchQuery, false)
            clearFocus()
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?) = true
                override fun onQueryTextChange(newText: String?): Boolean {
                    viewModel.searchQuery = newText.orEmpty()
                    if (sourceListReady) refreshSources()
                    return true
                }
            })
        }
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
            val indices = adapter.getItems()
            val selectAll = indices.all { !viewModel.canImportSource(it) || viewModel.selectStatus[it] }
            indices.forEach { viewModel.setSelection(it, !selectAll) }
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
                refreshSources()
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
                showPendingReplacementDialog()
            }
        }
        val source = arguments?.getString("source")
        if (source.isNullOrEmpty()) {
            dismiss()
            return
        }
        viewModel.importSource(source)
    }

    private fun refreshSources() {
        adapter.setItems(viewModel.allSources.indices.filter { index ->
            val source = viewModel.allSources[index]
            when (viewModel.searchQuery) {
                getString(R.string.enabled) -> source.enabled
                getString(R.string.disabled) -> !source.enabled
                getString(R.string.need_login) -> !source.loginUrl.isNullOrBlank()
                getString(R.string.no_group) -> source.sourceGroup.isNullOrBlank() ||
                    source.sourceGroup?.trim() == "未分组"
                else -> matchesSourceImportSearch(viewModel.searchQuery, source.sourceName,
                    source.sourceUrl, source.sourceGroup, source.sourceComment)
            }
        })
        if (adapter.itemCount == 0) {
            binding.tvMsg.setText(R.string.import_no_results)
            binding.tvMsg.visible()
        } else {
            binding.tvMsg.gone()
        }
        upSelectText()
        updateInteractionState()
    }

    private fun upSelectText() {
        if (viewModel.searchQuery.isNotEmpty()) {
            val indices = adapter.getItems()
            val selected = indices.count { viewModel.selectStatus[it] }
            val all = indices.all { !viewModel.canImportSource(it) || viewModel.selectStatus[it] }
            binding.tvFooterLeft.text = getString(
                if (all) R.string.import_unselect_results else R.string.import_select_results,
                selected, indices.size, viewModel.selectCount,
            )
            return
        }
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
            findItem(R.id.menu_remember_source_group)
                ?.isChecked = AppConfig.importRememberGroup
            findItem(R.id.menu_replace_source)
                ?.isChecked = viewModel.useSourceReplacement
            findItem(R.id.menu_select_new_source)?.isVisible = false // 暂不支持
            findItem(R.id.menu_select_update_source)?.isVisible = false // 暂不支持
        }
        updateGroupMenu()
    }

    private fun updateGroupMenu() {
        val item = binding.toolBar.menu.findItem(R.id.menu_new_group)
        val name = viewModel.groupName
        item.title = if (name.isNullOrBlank()) getString(R.string.diy_source_group) else {
            val title = getString(R.string.diy_edit_source_group_title, name)
            if (viewModel.isAddGroup) "+$title" else title
        }
    }

    @SuppressLint("InflateParams", "NotifyDataSetChanged")
    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_new_group -> alertCustomGroup()
            R.id.menu_replace_rule -> onOpenReplaceRules()
            R.id.menu_effective_replaces -> showSourceReplacements(false)
            R.id.menu_manual_replace_rule -> showSourceReplacements(true)
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

            R.id.menu_remember_source_group -> {
                item.isChecked = !item.isChecked
                AppConfig.importRememberGroup = item.isChecked
                if (item.isChecked) {
                    AppConfig.importLastGroup = viewModel.groupName
                    AppConfig.importLastGroupAdd = viewModel.isAddGroup
                } else {
                    viewModel.groupName = null
                    viewModel.isAddGroup = false
                }
                updateGroupMenu()
            }

            R.id.menu_replace_source -> {
                item.isChecked = !item.isChecked
                viewModel.setUseSourceReplacement(item.isChecked)
            }
        }
        return false
    }

    private fun alertCustomGroup() {
        alert(R.string.diy_edit_source_group) {
            val alertBinding = DialogCustomGroupBinding.inflate(layoutInflater).apply {
                val groups = appDb.rssSourceDao.allGroups()
                textInputLayout.setHint(R.string.group_name)
                editView.setFilterValues(groups.toList())
                editView.dropDownHeight = 180.dpToPx()
                editView.setText(viewModel.groupName)
                swAddGroup.isChecked = viewModel.isAddGroup
            }
            customView {
                alertBinding.root
            }
            okButton {
                viewModel.isAddGroup = alertBinding.swAddGroup.isChecked
                viewModel.groupName = alertBinding.editView.text?.toString()
                if (AppConfig.importRememberGroup) {
                    AppConfig.importLastGroup = viewModel.groupName
                    AppConfig.importLastGroupAdd = viewModel.isAddGroup
                }
                updateGroupMenu()
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

    private fun parseDraftSource(code: String): RssSource? = runCatching {
        parseSingleRssSourceJson(code).also { require(it.sourceUrl.isNotBlank()) }
    }.getOrNull()

    override fun onResume() {
        super.onResume()
        showPendingReplacementDialog()
    }

    private fun showPendingReplacementDialog() {
        if (!isResumed || childFragmentManager.isStateSaved || viewModel.sourceUpdatePending.value == true) return
        val (manual, index) = viewModel.pendingReplacementDialog ?: return
        viewModel.pendingReplacementDialog = null
        showSourceReplacements(manual, index)
    }

    private fun showSourceReplacements(manual: Boolean, index: Int = -1) {
        if (!isAdded || childFragmentManager.isStateSaved || viewModel.sourceUpdatePending.value == true) return
        if (manual) {
            if (!AppConfig.manualSourceReplaceRule) return
            showDialogFragment(ManualReplaceRulesDialog(viewModel.selectedManualRuleIds(index), index.toString()))
        } else {
            showDialogFragment(EffectiveReplacesDialog(viewModel.effectiveRuleIds(index)))
        }
    }

    override fun onShowSourceReplacements(code: String, requestId: String?, manual: Boolean) {
        val index = requestId?.toIntOrNull() ?: return
        val source = parseDraftSource(code) ?: run {
            toastOnUi(R.string.wrong_format)
            return
        }
        viewModel.refreshSourceReplacements(index, source, openDialog = manual)
    }

    override fun onManualSourceRulesSelected(ids: List<Long>, requestId: String?) {
        val index = requestId?.toIntOrNull() ?: -1
        val dialog = openCodeDialog()?.takeIf { it.requestId?.toIntOrNull() == index }
        val source = dialog?.let { parseDraftSource(it.currentOriginalCode()) }
        if (dialog != null && source == null) {
            toastOnUi(R.string.wrong_format)
            return
        }
        viewModel.refreshSourceReplacements(index, source, ids)
    }

    override fun onEffectiveSourceRulesChanged() {
        val dialog = openCodeDialog()
        if (dialog == null) viewModel.refreshSourceReplacements()
        else {
            pendingReplacementRefresh = dialog.currentOriginalCode() to dialog.requestId
            startPendingReplacementRefresh()
        }
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
        binding.tvFooterLeft.isEnabled = importEnabled && adapter.itemCount > 0
        binding.tvCancel.isEnabled = !sourceUpdatePending
        isCancelable = !sourceUpdatePending
        binding.toolBar.menu.findItem(R.id.menu_effective_replaces).isEnabled = importEnabled
        binding.toolBar.menu.findItem(R.id.menu_replace_rule).isEnabled = importEnabled
        binding.toolBar.menu.findItem(R.id.menu_manual_replace_rule).apply {
            isVisible = AppConfig.manualSourceReplaceRule
            isEnabled = importEnabled
        }
        binding.toolBar.menu.findItem(R.id.menu_replace_source).isVisible = !AppConfig.manualSourceReplaceRule
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
        RecyclerAdapter<Int, ItemSourceImportBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemSourceImportBinding {
            return ItemSourceImportBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemSourceImportBinding,
            item: Int,
            payloads: MutableList<Any>
        ) {
            binding.apply {
                val position = item
                val source = viewModel.allSources.getOrNull(position) ?: return
                val canImport = viewModel.canImportSource(position)
                val interactionEnabled = viewModel.sourceUpdatePending.value != true
                val replacementError = viewModel.sourceReplacementError(position)
                    ?.takeIf { viewModel.useSourceReplacement }
                cbSourceName.isChecked = viewModel.selectStatus[position]
                cbSourceName.isEnabled = canImport && interactionEnabled
                cbSourceName.text = source.sourceName
                val comment = replacementError?.let {
                    getString(R.string.source_replacement_error, it)
                } ?: source.sourceComment?.takeIf {
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
                        source.lastUpdateTime > localSource.lastUpdateTime ->
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
                    val position = getItem(holder.bindingAdapterPosition) ?: return@setOnUserCheckedChangeListener
                    viewModel.setSelection(position, isChecked)
                    upSelectText()
                }
                root.onClick {
                    val position = getItem(holder.bindingAdapterPosition) ?: return@onClick
                    if (viewModel.sourceUpdatePending.value == true ||
                        !viewModel.canImportSource(position)
                    ) {
                        return@onClick
                    }
                    cbSourceName.isChecked = !cbSourceName.isChecked
                    viewModel.setSelection(position, cbSourceName.isChecked)
                    upSelectText()
                }
                tvOpen.setOnClickListener {
                    if (viewModel.sourceUpdatePending.value == true) {
                        return@setOnClickListener
                    }
                    val position = getItem(holder.bindingAdapterPosition) ?: return@setOnClickListener
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
