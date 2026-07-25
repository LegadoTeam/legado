package io.legado.app.ui.highlight

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.core.view.isGone
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightRule
import io.legado.app.databinding.ActivityHighlightRuleBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.highlight.edit.HighlightRuleEditDialog
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class HighlightRuleActivity :
    VMBaseActivity<ActivityHighlightRuleBinding, HighlightRuleViewModel>(),
    HighlightRuleAdapter.CallBack {

    override val binding by viewBinding(ActivityHighlightRuleBinding::inflate)
    override val viewModel by viewModels<HighlightRuleViewModel>()
    private val adapter by lazy { HighlightRuleAdapter(this, this) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.recyclerView.run {
            setEdgeEffectColor(primaryColor)
            layoutManager = LinearLayoutManager(this@HighlightRuleActivity)
            adapter = this@HighlightRuleActivity.adapter
            addItemDecoration(VerticalDivider(this@HighlightRuleActivity))
            applyNavigationBarPadding()
        }
        ItemTouchHelper(ItemTouchCallback(adapter).apply { isCanDrag = true })
            .attachToRecyclerView(binding.recyclerView)
        observeData()
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
        return super.onCompatOptionsItemSelected(item)
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
        viewModel.update(*rule).onFinally { ReadBook.upHighlightRules() }
    }

    override fun delete(rule: HighlightRule) {
        alert(R.string.highlight_rule) {
            setMessage(getString(R.string.sure_del) + "\n" + rule.getDisplayName())
            noButton()
            yesButton {
                viewModel.delete(rule).onFinally { ReadBook.upHighlightRules() }
            }
        }
    }

    override fun edit(rule: HighlightRule) {
        showDialogFragment(HighlightRuleEditDialog.edit(rule.id))
    }

    override fun toTop(rule: HighlightRule) {
        viewModel.toTop(rule).onFinally { ReadBook.upHighlightRules() }
    }

    override fun toBottom(rule: HighlightRule) {
        viewModel.toBottom(rule).onFinally { ReadBook.upHighlightRules() }
    }
}
