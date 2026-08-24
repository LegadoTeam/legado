package io.legado.app.ui.replace.edit

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.databinding.ActivityReplaceEditBinding
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.widget.keyboard.KeyboardToolPop
import io.legado.app.utils.GSON
import io.legado.app.utils.imeHeight
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 编辑替换规则
 */
class ReplaceEditActivity :
    VMBaseActivity<ActivityReplaceEditBinding, ReplaceEditViewModel>(),
    KeyboardToolPop.CallBack {

    companion object {

        private const val PREVIEW_DEBOUNCE_MILLIS = 250L

        fun startIntent(
            context: Context,
            id: Long = -1,
            pattern: String? = null,
            isRegex: Boolean = false,
            scope: String? = null
        ): Intent {
            val intent = Intent(context, ReplaceEditActivity::class.java)
            intent.putExtra("id", id)
            intent.putExtra("pattern", pattern)
            intent.putExtra("isRegex", isRegex)
            intent.putExtra("scope", scope)
            return intent
        }

    }

    override val binding by viewBinding(ActivityReplaceEditBinding::inflate)
    override val viewModel by viewModels<ReplaceEditViewModel>()

    private val softKeyboardTool by lazy {
        KeyboardToolPop(this, lifecycleScope, binding.root, this)
    }

    private var previewJob: Job? = null
    private var updatingView = false

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        softKeyboardTool.attachToWindow(window)
        initView()
        viewModel.initData(intent) {
            upReplaceView(it)
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.replace_edit, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    private val textEditLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val view = window.decorView.findFocus()
            if (view is EditText) {
                result.data?.getStringExtra("text")?.let {
                    view.setText(it)
                }
                result.data?.getIntExtra("cursorPosition", -1)?.takeIf { it in 0 ..< view.text.length }?.let {
                    view.setSelection(it)
                }
            } else {
                toastOnUi(R.string.focus_lost_on_textbox)
            }
        }
    }
    private fun onFullEditClicked() {
        val view = window.decorView.findFocus()
        if (view is EditText && view !== binding.etPreviewOutput) {
            val hint = findParentTextInputLayout(view)?.hint?.toString()
            val currentText = view.text.toString()
            val intent = Intent(this, CodeEditActivity::class.java).apply {
                putExtra("text", currentText)
                putExtra("title", hint)
                putExtra("cursorPosition", view.selectionStart)
            }
            textEditLauncher.launch(intent)
        }
        else {
            toastOnUi(R.string.please_focus_cursor_on_textbox)
        }
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_fullscreen_edit -> onFullEditClicked()
            R.id.menu_save -> {
                val rule = getReplaceRule()
                viewModel.save(rule) {
                    viewModel.saveSample(rule.id, binding.etPreviewInput.text.toString())
                    setResult(RESULT_OK)
                    finish()
                }
            }

            R.id.menu_copy_rule -> sendToClip(GSON.toJson(getReplaceRuleForExport()))
            R.id.menu_paste_rule -> viewModel.pasteRule {
                upReplaceView(it)
            }
        }
        return true
    }

    override fun onDestroy() {
        previewJob?.cancel()
        super.onDestroy()
        softKeyboardTool.dismiss()
    }

    private fun initView() {
        binding.ivHelp.setOnClickListener {
            showHelp("regexHelp")
        }
        binding.etPreviewOutput.apply {
            keyListener = null
            showSoftInputOnFocus = false
            isCursorVisible = false
            setTextIsSelectable(true)
        }
        binding.etPreviewInput.doAfterTextChanged { text ->
            if (updatingView) return@doAfterTextChanged
            val value = text?.toString().orEmpty()
            val normalized = ReplacePreview.normalizeSample(value)
            if (value != normalized) {
                updatingView = true
                try {
                    binding.etPreviewInput.setText(normalized)
                    binding.etPreviewInput.setSelection(normalized.length)
                } finally {
                    updatingView = false
                }
                toastOnUi(getString(R.string.replace_preview_truncated, ReplacePreview.MAX_SAMPLE_LENGTH))
            }
            schedulePreview()
        }
        binding.etName.doAfterTextChanged { schedulePreview() }
        binding.etReplaceRule.doAfterTextChanged { schedulePreview() }
        binding.etReplaceTo.doAfterTextChanged { schedulePreview() }
        binding.etTimeout.doAfterTextChanged { schedulePreview() }
        binding.cbUseRegex.setOnCheckedChangeListener { _, _ -> schedulePreview() }
        binding.root.setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
            softKeyboardTool.initialPadding = windowInsets.imeHeight
            windowInsets
        }
    }

    private fun upReplaceView(replaceRule: ReplaceRule) = binding.run {
        updatingView = true
        try {
            etName.setText(replaceRule.name)
            etGroup.setText(replaceRule.group)
            etReplaceRule.setText(replaceRule.pattern)
            cbUseRegex.isChecked = replaceRule.isRegex
            etReplaceTo.setText(replaceRule.replacement)
            cbScopeTitle.isChecked = replaceRule.scopeTitle
            cbScopeSource.isChecked = replaceRule.scopeSource
            cbScopeContent.isChecked = replaceRule.scopeContent
            etScope.setText(replaceRule.scope)
            etExcludeScope.setText(replaceRule.excludeScope)
            etTimeout.setText(replaceRule.timeoutMillisecond.toString())
            val editingRuleId = viewModel.replaceRule?.id ?: replaceRule.id
            etPreviewInput.setText(
                ReplacePreview.normalizeSample(
                    replaceRule.previewText ?: viewModel.sampleFor(editingRuleId)
                )
            )
            etPreviewInput.setSelection(etPreviewInput.text?.length ?: 0)
        } finally {
            updatingView = false
        }
        schedulePreview()
    }

    private fun schedulePreview() {
        if (updatingView) return
        previewJob?.cancel()
        val sample = ReplacePreview.normalizeSample(binding.etPreviewInput.text.toString())
        val rule = getReplaceRule().copy()
        previewJob = lifecycleScope.launch {
            delay(PREVIEW_DEBOUNCE_MILLIS)
            val result = try {
                Result.success(ReplacePreview.apply(rule, sample))
            } catch (error: CancellationException) {
                throw error
            } catch (error: StackOverflowError) {
                Result.failure(error)
            } catch (error: Exception) {
                Result.failure(error)
            }
            if (!isActive) return@launch
            result.onSuccess {
                binding.tilPreviewOutput.error = null
                binding.etPreviewOutput.setText(it)
            }.onFailure {
                binding.etPreviewOutput.setText(sample)
                binding.tilPreviewOutput.error = getString(
                    when ((it as? ReplacePreviewException)?.reason) {
                        ReplacePreviewException.Reason.TIMEOUT -> R.string.replace_preview_timeout
                        ReplacePreviewException.Reason.CONTEXT_UNAVAILABLE ->
                            R.string.replace_preview_context_unavailable
                        ReplacePreviewException.Reason.JS_EVALUATION ->
                            R.string.replace_preview_js_error
                        null -> R.string.replace_preview_error
                    }
                )
            }
        }
    }

    private fun getReplaceRule(): ReplaceRule = binding.run {
        val replaceRule: ReplaceRule = viewModel.replaceRule ?: ReplaceRule()
        replaceRule.name = etName.text.toString()
        replaceRule.group = etGroup.text.toString()
        replaceRule.pattern = etReplaceRule.text.toString()
        replaceRule.isRegex = cbUseRegex.isChecked
        replaceRule.replacement = etReplaceTo.text.toString()
        replaceRule.scopeTitle = cbScopeTitle.isChecked
        replaceRule.scopeSource = cbScopeSource.isChecked
        replaceRule.scopeContent = cbScopeContent.isChecked
        replaceRule.scope = etScope.text.toString()
        replaceRule.excludeScope = etExcludeScope.text.toString()
        replaceRule.timeoutMillisecond = etTimeout.text.toString().toLongOrNull() ?: 3000L
        return replaceRule
    }

    private fun getReplaceRuleForExport(): ReplaceRule {
        return getReplaceRule().also { rule ->
            rule.previewText = ReplacePreview.normalizeSample(
                binding.etPreviewInput.text.toString()
            ).takeIf { it.isNotEmpty() }
        }
    }

    override fun helpActions(): List<SelectItem<String>> {
        return arrayListOf(
            SelectItem("正则教程", "regexHelp")
        )
    }

    override fun onHelpActionSelect(action: String) {
        when (action) {
            "regexHelp" -> showHelp("regexHelp")
        }
    }

    override fun sendText(text: String) {
        if (text.isEmpty()) return
        val view = window?.decorView?.findFocus()
        if (view is EditText && view !== binding.etPreviewOutput) {
            var start = view.selectionStart
            var end = view.selectionEnd
            if (start > end) {
                val temp = start
                start = end
                end = temp
            }
            //获取EditText的文字
            val edit = view.editableText
            if (start < 0 || start >= edit.length) {
                edit.append(text)
            } else {
                //光标所在位置插入文字
                edit.replace(start, end, text)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onUndoClicked() {
        val editText = window.decorView.findFocus()
        if (editText is EditText && editText !== binding.etPreviewOutput) {
            editText.onTextContextMenuItem(android.R.id.undo)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onRedoClicked() {
        val editText = window.decorView.findFocus()
        if (editText is EditText && editText !== binding.etPreviewOutput) {
            editText.onTextContextMenuItem(android.R.id.redo)
        }
    }

}
