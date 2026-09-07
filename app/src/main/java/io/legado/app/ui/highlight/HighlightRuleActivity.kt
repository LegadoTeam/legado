package io.legado.app.ui.highlight

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isGone
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.HighlightRuleFile
import io.legado.app.databinding.ActivityHighlightRuleBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.association.ImportHighlightRuleDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.highlight.edit.HighlightRuleEditDialog
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.GSON
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class HighlightRuleActivity :
    VMBaseActivity<ActivityHighlightRuleBinding, HighlightRuleViewModel>(),
    PopupMenu.OnMenuItemClickListener,
    SelectActionBar.CallBack,
    HighlightRuleAdapter.CallBack {

    override val binding by viewBinding(ActivityHighlightRuleBinding::inflate)
    override val viewModel by viewModels<HighlightRuleViewModel>()
    private val adapter by lazy { HighlightRuleAdapter(this, this) }
    private val importDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri -> showDialogFragment(ImportHighlightRuleDialog(uri.toString())) }
    }
    private val exportResult = registerForActivityResult(HandleFileContract()) {
        if (it.uri != null) toastOnUi(R.string.export_success)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initRecyclerView()
        initSelectActionView()
        observeData()
    }

    private fun initRecyclerView() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        val itemTouchCallback = ItemTouchCallback(adapter).apply { isCanDrag = true }
        DragSelectTouchHelper(adapter.dragSelectCallback)
            .setSlideArea(16, 50)
            .also { helper ->
                helper.attachToRecyclerView(binding.recyclerView)
                helper.activeSlideSelect()
            }
        ItemTouchHelper(itemTouchCallback).attachToRecyclerView(binding.recyclerView)
    }

    private fun initSelectActionView() {
        binding.selectActionBar.setMainActionText(R.string.delete)
        binding.selectActionBar.inflateMenu(R.menu.replace_rule_sel)?.apply {
            findItem(R.id.menu_add_group)?.isVisible = false
            findItem(R.id.menu_remove_group)?.isVisible = false
        }
        binding.selectActionBar.setOnMenuItemClickListener(this)
        binding.selectActionBar.setCallBack(this)
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.highlight_rule, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_add_highlight_rule) {
            showDialogFragment(HighlightRuleEditDialog.create(pattern = ""))
            return true
        }
        if (item.itemId == R.id.menu_import_local) {
            importDoc.launch {
                mode = HandleFileContract.FILE
                allowExtensions = arrayOf("json")
            }
            return true
        }
        if (item.itemId == R.id.menu_export_all) {
            exportRules(adapter.getItems())
            return true
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        val selection = adapter.selection
        when (item.itemId) {
            R.id.menu_enable_selection ->
                viewModel.enableSelection(selection, true).onSuccess { ReadBook.upHighlightRules() }
            R.id.menu_disable_selection ->
                viewModel.enableSelection(selection, false).onSuccess { ReadBook.upHighlightRules() }
            R.id.menu_top_sel ->
                viewModel.moveSelection(selection, true).onSuccess { ReadBook.upHighlightRules() }
            R.id.menu_bottom_sel ->
                viewModel.moveSelection(selection, false).onSuccess { ReadBook.upHighlightRules() }
            R.id.menu_export_selection -> exportRules(selection)
        }
        return true
    }

    override fun selectAll(selectAll: Boolean) {
        if (selectAll) adapter.selectAll() else adapter.revertSelection()
    }

    override fun revertSelection() = adapter.revertSelection()

    override fun onClickSelectBarMainAction() {
        alert(titleResource = R.string.highlight_rule, messageResource = R.string.sure_del) {
            noButton()
            yesButton {
                viewModel.delete(*adapter.selection.toTypedArray())
                    .onSuccess { ReadBook.upHighlightRules() }
            }
        }
    }

    private fun exportRules(rules: List<HighlightRule>) {
        if (rules.isEmpty()) {
            toastOnUi(R.string.highlight_rule_empty)
            return
        }
        exportResult.launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData(
                "HighlightRules.json",
                GSON.toJson(
                    HighlightRuleFile(type = HighlightRuleFile.TYPE, rules = rules)
                ).toByteArray(),
                "application/json"
            )
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            appDb.highlightRuleDao.flowAll()
                .catch { AppLog.put("高亮规则界面更新数据出错", it) }
                .flowOn(IO)
                .conflate()
                .collect { rules ->
                    binding.tvEmptyMsg.isGone = rules.isNotEmpty()
                    adapter.setItems(rules, adapter.diffItemCallBack)
                }
        }
    }

    override fun update(vararg rule: HighlightRule) {
        viewModel.update(*rule).onSuccess { ReadBook.upHighlightRules() }
    }

    override fun delete(rule: HighlightRule) {
        alert(R.string.highlight_rule) {
            setMessage(getString(R.string.sure_del) + "\n" + rule.getDisplayName())
            noButton()
            yesButton {
                viewModel.delete(rule).onSuccess { ReadBook.upHighlightRules() }
            }
        }
    }

    override fun edit(rule: HighlightRule) {
        showDialogFragment(HighlightRuleEditDialog.edit(rule.id))
    }

    override fun toTop(rule: HighlightRule) {
        viewModel.toTop(rule).onSuccess { ReadBook.upHighlightRules() }
    }

    override fun toBottom(rule: HighlightRule) {
        viewModel.toBottom(rule).onSuccess { ReadBook.upHighlightRules() }
    }

    override fun upCountView() {
        binding.selectActionBar.upCountView(adapter.selection.size, adapter.itemCount)
    }

    override fun onResume() {
        super.onResume()
        adapter.upResumed(true)
    }

    override fun onPause() {
        adapter.upResumed(false)
        super.onPause()
    }
}
