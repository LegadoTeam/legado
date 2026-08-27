package io.legado.app.ui.autoTask

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import androidx.activity.addCallback
import androidx.core.view.children
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.databinding.ActivityAutoTaskEditBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.AutoTask
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.widget.bindFieldNavigation
import io.legado.app.ui.widget.setFieldLabels
import io.legado.app.ui.widget.text.TextInputLayout
import io.legado.app.utils.CronSchedule
import io.legado.app.utils.GSON
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
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
    private var originTask: AutoTaskRule? = null
    private var pendingEditViewId = View.NO_ID
    private var pendingEditText: String? = null
    private var pendingEditCursor = -1

    private val textEditLauncher = registerForActivityResult(
        StartActivityContract(CodeEditActivity::class.java)
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            clearPendingEditResult()
            return@registerForActivityResult
        }
        result.data?.getStringExtra("text")?.let { pendingEditText = it }
        if (pendingEditText == null) {
            clearPendingEditResult()
            return@registerForActivityResult
        }
        pendingEditCursor = result.data?.getIntExtra("cursorPosition", -1) ?: -1
        applyPendingEditResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        pendingEditViewId = savedInstanceState?.getInt(STATE_PENDING_EDIT_VIEW_ID) ?: View.NO_ID
        pendingEditText = savedInstanceState?.getString(STATE_PENDING_EDIT_TEXT)
        pendingEditCursor = savedInstanceState?.getInt(STATE_PENDING_EDIT_CURSOR) ?: -1
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_PENDING_EDIT_VIEW_ID, pendingEditViewId)
        outState.putString(STATE_PENDING_EDIT_TEXT, pendingEditText)
        outState.putInt(STATE_PENDING_EDIT_CURSOR, pendingEditCursor)
        super.onSaveInstanceState(outState)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        onBackPressedDispatcher.addCallback(this) { finish() }
        initFieldNavigation()
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

    private fun initFieldNavigation() = binding.run {
        val fields = fieldContainer.children.filterIsInstance<TextInputLayout>().toList()
        fieldNav.setFieldLabels(fields.map { it.hint.toString() })
        fieldNav.bindFieldNavigation(scrollView, fields)
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.auto_task_edit, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_fullscreen_edit -> onFullEditClicked()
            R.id.menu_save -> save { finish() }
            R.id.menu_debug_source -> save { saved ->
                startActivity(AutoTaskDebugActivity.intent(this, saved.id))
            }
            R.id.menu_login -> save { saved ->
                if (!AutoTask.buildSource(saved).hasLogin()) {
                    toastOnUi(R.string.source_no_login)
                } else {
                    startActivity<SourceLoginActivity> {
                        putExtra("type", "autoTask")
                        putExtra("key", saved.id)
                    }
                }
            }
            R.id.menu_copy_rule -> copyRule()
            R.id.menu_paste_rule -> pasteRule()
            R.id.menu_help -> showHelp("autoTaskHelp")
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun bind(rule: AutoTaskRule) {
        applyRule(rule)
        originTask = buildDraft()
        applyPendingEditResult()
    }

    private fun applyRule(rule: AutoTaskRule) {
        binding.run {
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
    }

    private fun onFullEditClicked() {
        if (originTask == null) {
            toastOnUi(R.string.loading)
            return
        }
        val view = window.decorView.findFocus() as? EditText
        val field = view?.let { editField(it.id) }
        if (view == null || field == null) {
            toastOnUi(R.string.please_focus_cursor_on_textbox)
            return
        }
        pendingEditViewId = view.id
        pendingEditText = view.text.toString()
        pendingEditCursor = -1
        textEditLauncher.launch {
            putExtra("text", view.text.toString())
            putExtra("title", getString(field.second))
            putExtra("cursorPosition", view.selectionStart.coerceAtLeast(0))
            putExtra("returnUnchangedText", true)
        }
    }

    private fun editField(viewId: Int): Pair<EditText, Int>? {
        return when (viewId) {
            R.id.et_script -> binding.etScript to R.string.auto_task_script
            R.id.et_header -> binding.etHeader to R.string.auto_task_header
            R.id.et_js_lib -> binding.etJsLib to R.string.auto_task_js_lib
            R.id.et_login_ui -> binding.etLoginUi to R.string.login_ui
            R.id.et_login_check_js -> binding.etLoginCheckJs to R.string.login_check_js
            else -> null
        }
    }

    private fun applyPendingEditResult() {
        if (originTask == null) return
        val text = pendingEditText ?: return
        val view = editField(pendingEditViewId)?.first ?: run {
            clearPendingEditResult()
            return
        }
        view.setText(text)
        if (pendingEditCursor >= 0) {
            view.setSelection(pendingEditCursor.coerceIn(0, text.length))
        }
        view.requestFocus()
        clearPendingEditResult()
    }

    private fun clearPendingEditResult() {
        pendingEditViewId = View.NO_ID
        pendingEditText = null
        pendingEditCursor = -1
    }

    private fun copyRule() {
        if (originTask == null) {
            toastOnUi(R.string.loading)
            return
        }
        val draft = buildDraft()
        lifecycleScope.launch {
            val json = withContext(Dispatchers.Default) {
                AutoTask.exportJson(listOf(draft))
            }
            sendToClip(json)
        }
    }

    private fun pasteRule() {
        if (originTask == null) {
            toastOnUi(R.string.loading)
            return
        }
        val text = getClipText() ?: run {
            toastOnUi(R.string.wrong_format)
            return
        }
        lifecycleScope.launch {
            val pasted = withContext(Dispatchers.Default) {
                GSON.fromJsonObject<AutoTaskRule>(text).getOrNull()
                    ?: GSON.fromJsonArray<AutoTaskRule>(text).getOrNull()?.singleOrNull()
            }
            if (pasted == null) {
                toastOnUi(R.string.wrong_format)
            } else {
                applyRule(pasted)
            }
        }
    }

    private fun save(after: (AutoTaskRule) -> Unit) {
        val draft = buildRule() ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val saved = AutoTask.upsert(draft, this@AutoTaskEditActivity)
            withContext(Dispatchers.Main) {
                task = saved
                originTask = saved
                setResult(RESULT_OK)
                after(saved)
            }
        }
    }

    private fun buildRule(): AutoTaskRule? {
        val draft = buildDraft()
        if (draft.name.isBlank()) {
            toastOnUi(R.string.auto_task_name_required)
            return null
        }
        if (CronSchedule.parse(draft.cron.orEmpty()) == null) {
            toastOnUi(R.string.auto_task_cron_invalid)
            return null
        }
        if (AutoTask.normalizeScript(draft.script).isBlank()) {
            toastOnUi(R.string.auto_task_script_empty)
            return null
        }
        return draft
    }

    private fun buildDraft(): AutoTaskRule = binding.run {
        task.copy(
            name = etName.text?.toString()?.trim().orEmpty(),
            enable = cbEnable.isChecked,
            cron = etCron.text?.toString()?.trim().orEmpty(),
            comment = textOrNull(etComment.text?.toString()),
            script = etScript.text?.toString().orEmpty(),
            header = textOrNull(etHeader.text?.toString()),
            jsLib = textOrNull(etJsLib.text?.toString()),
            concurrentRate = textOrNull(etConcurrentRate.text?.toString()),
            loginUrl = textOrNull(etLoginUrl.text?.toString()),
            loginUi = textOrNull(etLoginUi.text?.toString()),
            loginCheckJs = textOrNull(etLoginCheckJs.text?.toString()),
            enabledCookieJar = cbCookieJar.isChecked
        )
    }

    override fun finish() {
        val changed = originTask?.let { it != buildDraft() } == true
        if (changed) {
            alert(R.string.exit) {
                setMessage(R.string.exit_no_save)
                positiveButton(R.string.yes)
                negativeButton(R.string.no) {
                    super.finish()
                }
            }
        } else {
            super.finish()
        }
    }

    private fun textOrNull(value: String?): String? = value?.trim()?.ifBlank { null }

    companion object {
        private const val EXTRA_ID = "autoTaskId"
        private const val STATE_PENDING_EDIT_VIEW_ID = "pendingEditViewId"
        private const val STATE_PENDING_EDIT_TEXT = "pendingEditText"
        private const val STATE_PENDING_EDIT_CURSOR = "pendingEditCursor"

        fun intent(context: Context, id: String): Intent {
            return Intent(context, AutoTaskEditActivity::class.java).putExtra(EXTRA_ID, id)
        }
    }
}
