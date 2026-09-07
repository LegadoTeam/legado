package io.legado.app.ui.autoTask

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.databinding.ActivityAutoTaskBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.SourceSharePassphrase
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.sourceSharePassphraseButton
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.model.AutoTask
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.CronSchedule
import io.legado.app.utils.applyTint
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.ACache
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections.swap

class AutoTaskActivity : BaseActivity<ActivityAutoTaskBinding>(), AutoTaskAdapter.Callback,
    SelectActionBar.CallBack, PopupMenu.OnMenuItemClickListener, SearchView.OnQueryTextListener {

    override val binding by viewBinding(ActivityAutoTaskBinding::inflate)
    private val adapter by lazy { AutoTaskAdapter(this, this) }
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }
    private var allRules = emptyList<AutoTaskRule>()
    private val importRecordKey = "autoTaskRecordKey"
    private val importDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri -> showDialogFragment(ImportAutoTaskDialog(uri.toString())) }
    }
    private val exportDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            val url = uri.toString()
            alert(R.string.export_success) {
                if (url.isAbsUrl()) {
                    setMessage(DirectLinkUpload.getSummary())
                    sourceSharePassphraseButton(
                        layoutInflater,
                        url,
                        SourceSharePassphrase.Type.AUTO_TASK,
                    )
                }
                val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                    editView.hint = getString(R.string.path)
                    editView.setText(url)
                }
                customView { alertBinding.root }
                okButton {
                    sendToClip(url)
                }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        searchView.applyTint(primaryTextColor)
        searchView.queryHint = getString(R.string.search)
        searchView.setOnQueryTextListener(this)
        DragSelectTouchHelper(adapter.dragSelectCallback).apply {
            setSlideArea(16, 50)
            attachToRecyclerView(binding.recyclerView)
            activeSlideSelect()
        }
        binding.selectActionBar.setMainActionText(R.string.delete)
        binding.selectActionBar.inflateMenu(R.menu.auto_task_sel)
        binding.selectActionBar.setCallBack(this)
        binding.selectActionBar.setOnMenuItemClickListener(this)
        upCountView()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { AutoTask.all() }
            appDb.autoTaskRuleDao.flowAll()
                .flowOn(Dispatchers.IO)
                .collectLatest { rules ->
                    allRules = rules
                    adapter.retainExistingSelections(rules)
                    updateTaskList()
                }
        }
    }

    private fun updateTaskList() {
        val query = searchView.query.toString().trim()
        val filtered = if (query.isEmpty()) {
            allRules
        } else {
            allRules.filter { it.name.contains(query, ignoreCase = true) }
        }
        adapter.setItems(filtered, adapter.diffCallback)
        binding.tvEmpty.isVisible = filtered.isEmpty()
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        updateTaskList()
        return false
    }

    override fun onQueryTextSubmit(query: String?): Boolean = false

    private fun showBatchCronDialog() {
        val ids = adapter.selection.map { it.id }
        if (ids.isEmpty()) return
        val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = getString(R.string.auto_task_cron_hint)
        }
        val dialog = alert(titleResource = R.string.auto_task_batch_cron) {
            customView { alertBinding.root }
            okButton()
            cancelButton()
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val cron = alertBinding.editView.text?.toString()?.trim().orEmpty()
            if (CronSchedule.parse(cron) == null) {
                alertBinding.editView.error = getString(R.string.auto_task_cron_invalid)
                return@setOnClickListener
            }
            lifecycleScope.launch(Dispatchers.IO) {
                AutoTask.updateCron(ids, cron, this@AutoTaskActivity)
            }
            dialog.dismiss()
        }
    }

    private fun updateSelectionEnabled(enabled: Boolean) {
        val ids = adapter.selection.map { it.id }
        if (ids.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            AutoTask.updateEnabled(ids, enabled, this@AutoTaskActivity)
        }
    }

    private fun deleteSelection() {
        val ids = adapter.selection.map { it.id }
        if (ids.isEmpty()) return
        alert(R.string.delete, R.string.sure_del) {
            yesButton {
                lifecycleScope.launch(Dispatchers.IO) {
                    AutoTask.delete(ids, this@AutoTaskActivity)
                }
            }
            noButton()
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.auto_task, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onResume() {
        super.onResume()
        adapter.upResumed(true)
    }

    override fun onPause() {
        adapter.upResumed(false)
        super.onPause()
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add -> startActivity<AutoTaskEditActivity>()
            R.id.menu_import_local -> importDoc.launch {
                mode = HandleFileContract.FILE
                allowExtensions = arrayOf("txt", "json")
            }
            R.id.menu_import_on_line -> showImportDialog()
            R.id.menu_export -> lifecycleScope.launch {
                val json = withContext(Dispatchers.IO) { AutoTask.exportJson() }
                exportDoc.launch {
                    mode = HandleFileContract.EXPORT
                    fileData = HandleFileContract.FileData(
                        "exportAutoTask.json",
                        json,
                        "application/json"
                    )
                }
            }
            R.id.menu_help -> showHelp("autoTaskHelp")
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun showImportDialog() {
        val aCache = ACache.get(cacheDir = false)
        val cacheUrls = aCache.getAsString(importRecordKey)
            ?.splitNotBlank(",")
            ?.toMutableList() ?: mutableListOf()
        val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = "url"
            editView.setFilterValues(cacheUrls)
            editView.delCallBack = {
                cacheUrls.remove(it)
                aCache.put(importRecordKey, cacheUrls.joinToString(","))
            }
        }
        alert(titleResource = R.string.import_on_line) {
            customView { alertBinding.root }
            okButton {
                val source = alertBinding.editView.text?.toString()?.trim().orEmpty()
                if (source.isBlank()) {
                    toastOnUi(R.string.wrong_format)
                } else {
                    if (source.isAbsUrl() && !cacheUrls.contains(source)) {
                        cacheUrls.add(0, source)
                        aCache.put(importRecordKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(ImportAutoTaskDialog(source))
                }
            }
            cancelButton()
        }
    }

    override fun edit(task: AutoTaskRule) {
        startActivity(AutoTaskEditActivity.intent(this, task.id))
    }

    override fun debug(task: AutoTaskRule) {
        startActivity(AutoTaskDebugActivity.intent(this, task.id))
    }

    override fun toggle(task: AutoTaskRule, enabled: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            AutoTask.upsert(task.copy(enable = enabled), this@AutoTaskActivity)
        }
    }

    override fun move(task: AutoTaskRule, offset: Int) {
        val tasks = adapter.getItems().toMutableList()
        val position = tasks.indexOfFirst { it.id == task.id }
        if (position < 0) return
        val targetPosition = position + offset
        if (targetPosition !in tasks.indices) return
        swap(tasks, position, targetPosition)
        val orderedIds = tasks.map { it.id }
        lifecycleScope.launch(Dispatchers.IO) {
            AutoTask.reorder(orderedIds, this@AutoTaskActivity)
        }
    }

    override fun showLog(task: AutoTaskRule) {
        alert(task.name) {
            setMessage(
                task.lastLog ?: task.lastError ?: task.lastResult
                ?: getString(R.string.auto_task_no_log)
            )
            neutralButton(R.string.clear) {
                lifecycleScope.launch(Dispatchers.IO) {
                    AutoTask.clearRunLog(task.id)
                }
            }
            okButton()
        }
    }

    override fun delete(task: AutoTaskRule) {
        alert(R.string.delete) {
            setMessage(getString(R.string.auto_task_delete_confirm, task.name))
            yesButton {
                lifecycleScope.launch(Dispatchers.IO) {
                    AutoTask.delete(listOf(task.id), this@AutoTaskActivity)
                }
            }
            noButton()
        }
    }

    override fun upCountView() {
        binding.selectActionBar.upCountView(adapter.selectionCount, adapter.itemCount)
    }

    override fun selectAll(selectAll: Boolean) {
        if (selectAll) adapter.selectAll() else adapter.revertSelection()
    }

    override fun revertSelection() {
        adapter.revertSelection()
    }

    override fun onClickSelectBarMainAction() {
        deleteSelection()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_batch_cron -> showBatchCronDialog()
            R.id.menu_enable_selection -> updateSelectionEnabled(true)
            R.id.menu_disable_selection -> updateSelectionEnabled(false)
            R.id.menu_export_selection -> {
                val rules = adapter.selection
                lifecycleScope.launch {
                    val json = withContext(Dispatchers.IO) { AutoTask.exportJson(rules) }
                    exportDoc.launch {
                        mode = HandleFileContract.EXPORT
                        fileData = HandleFileContract.FileData(
                            "exportAutoTaskSelection.json",
                            json,
                            "application/json"
                        )
                    }
                }
            }
        }
        return true
    }
}
