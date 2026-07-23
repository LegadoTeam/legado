package io.legado.app.ui.autoTask

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.databinding.ActivityAutoTaskBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.AutoTask
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AutoTaskActivity : BaseActivity<ActivityAutoTaskBinding>(), AutoTaskAdapter.Callback {

    override val binding by viewBinding(ActivityAutoTaskBinding::inflate)
    private val adapter by lazy { AutoTaskAdapter(this, this) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { AutoTask.all() }
            appDb.autoTaskRuleDao.flowAll()
                .flowOn(Dispatchers.IO)
                .collectLatest { rules ->
                    adapter.setItems(rules, adapter.diffCallback)
                    binding.tvEmpty.isVisible = rules.isEmpty()
                }
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
            R.id.menu_help -> showHelp("autoTaskHelp")
        }
        return super.onCompatOptionsItemSelected(item)
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
        lifecycleScope.launch(Dispatchers.IO) {
            AutoTask.move(task.id, offset, this@AutoTaskActivity)
        }
    }

    override fun showLog(task: AutoTaskRule) {
        alert(task.name) {
            setMessage(task.lastLog ?: task.lastError ?: getString(R.string.auto_task_no_log))
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
}
