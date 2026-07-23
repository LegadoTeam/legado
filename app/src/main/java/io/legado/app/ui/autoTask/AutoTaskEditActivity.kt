package io.legado.app.ui.autoTask

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.databinding.ActivityAutoTaskEditBinding
import io.legado.app.model.AutoTask
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.utils.CronSchedule
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AutoTaskEditActivity : BaseActivity<ActivityAutoTaskEditBinding>() {

    override val binding by viewBinding(ActivityAutoTaskEditBinding::inflate)
    private var task = AutoTaskRule()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val id = intent.getStringExtra(EXTRA_ID)
        if (id == null) {
            bind(task)
        } else {
            lifecycleScope.launch(Dispatchers.IO) {
                val loaded = AutoTask.get(id)
                withContext(Dispatchers.Main) {
                    if (loaded == null) finish() else {
                        task = loaded
                        bind(loaded)
                    }
                }
            }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.auto_task_edit, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_save -> save { finish() }
            R.id.menu_debug_source -> save { saved ->
                startActivity(AutoTaskDebugActivity.intent(this, saved.id))
            }
            R.id.menu_login -> save { saved ->
                if (saved.loginUrl.isNullOrBlank()) {
                    toastOnUi(R.string.source_no_login)
                } else {
                    startActivity<SourceLoginActivity> {
                        putExtra("type", "autoTask")
                        putExtra("key", saved.id)
                    }
                }
            }
            R.id.menu_help -> showHelp("autoTaskHelp")
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun bind(rule: AutoTaskRule) = binding.run {
        cbEnable.isChecked = rule.enable
        cbCookieJar.isChecked = rule.enabledCookieJar
        etName.setText(rule.name)
        etCron.setText(rule.cron ?: AutoTask.DEFAULT_CRON)
        etComment.setText(rule.comment)
        etScript.setText(rule.script)
        etHeader.setText(rule.header)
        etJsLib.setText(rule.jsLib)
        etConcurrentRate.setText(rule.concurrentRate)
        etLoginUrl.setText(rule.loginUrl)
        etLoginUi.setText(rule.loginUi)
        etLoginCheckJs.setText(rule.loginCheckJs)
    }

    private fun save(after: (AutoTaskRule) -> Unit) {
        val draft = buildRule() ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val saved = AutoTask.upsert(draft, this@AutoTaskEditActivity)
            withContext(Dispatchers.Main) {
                task = saved
                setResult(RESULT_OK)
                after(saved)
            }
        }
    }

    private fun buildRule(): AutoTaskRule? = binding.run {
        val name = etName.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            toastOnUi(R.string.auto_task_name_required)
            return null
        }
        val cron = etCron.text?.toString()?.trim().orEmpty()
        if (CronSchedule.parse(cron) == null) {
            toastOnUi(R.string.auto_task_cron_invalid)
            return null
        }
        val script = etScript.text?.toString().orEmpty()
        if (AutoTask.normalizeScript(script).isBlank()) {
            toastOnUi(R.string.auto_task_script_empty)
            return null
        }
        task.copy(
            name = name,
            enable = cbEnable.isChecked,
            cron = cron,
            comment = textOrNull(etComment.text?.toString()),
            script = script,
            header = textOrNull(etHeader.text?.toString()),
            jsLib = textOrNull(etJsLib.text?.toString()),
            concurrentRate = textOrNull(etConcurrentRate.text?.toString()),
            loginUrl = textOrNull(etLoginUrl.text?.toString()),
            loginUi = textOrNull(etLoginUi.text?.toString()),
            loginCheckJs = textOrNull(etLoginCheckJs.text?.toString()),
            enabledCookieJar = cbCookieJar.isChecked
        )
    }

    private fun textOrNull(value: String?): String? = value?.trim()?.ifBlank { null }

    companion object {
        private const val EXTRA_ID = "autoTaskId"

        fun intent(context: Context, id: String): Intent {
            return Intent(context, AutoTaskEditActivity::class.java).putExtra(EXTRA_ID, id)
        }
    }
}
