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
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.code.CodeTextTransfer
import io.legado.app.ui.widget.code.EditSafety
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
    private var originalRule: ReplaceRule? = null
    private var originalSample = ""
    private var saved = false
    private val rawFields = mutableMapOf<Int, String>()
    private var pendingFieldId: Int? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        softKeyboardTool.attachToWindow(window)
        initView()
        viewModel.initData(intent) {
            originalRule = it.copy()
            originalSample = ReplacePreview.normalizeSample(it.previewText ?: viewModel.sampleFor(it.id))
            upReplaceView(it)
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.replace_edit, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    private val textEditLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val fieldId = pendingFieldId
            val view = fieldId?.let { findViewById<EditText>(it) }
            val text = result.data?.getStringExtra("text")
                ?: result.data?.getStringExtra("textFile")?.let { path ->
                    CodeTextTransfer.read(this, path).also { CodeTextTransfer.delete(this, path) }
                }
            if (view != null && fieldId != null && text != null) {
                rawFields[fieldId] = text
                renderField(view, fieldId, text)
                result.data?.getIntExtra("cursorPosition", -1)?.takeIf { it in 0 ..< view.text.length }?.let {
                    if (view.isFocusable) view.setSelection(it)
                }
                pendingFieldId = null
            } else {
                toastOnUi(R.string.focus_lost_on_textbox)
            }
        }
    }
    private fun onFullEditClicked(fieldId: Int? = null) {
        val view = fieldId?.let { findViewById<EditText>(it) } ?: window.decorView.findFocus()
        if (view is EditText && view !== binding.etPreviewOutput) {
            val hint = findParentTextInputLayout(view)?.hint?.toString()
            val currentText = rawFields[view.id] ?: view.text.toString()
            val intent = Intent(this, CodeEditActivity::class.java).apply {
                putExtra("useTextFile", true)
                if (currentText.length > EditSafety.MAX_INLINE_TEXT_LENGTH) {
                    putExtra("textFile", CodeTextTransfer.write(this@ReplaceEditActivity, currentText))
                } else putExtra("text", currentText)
                putExtra("title", hint)
                putExtra("cursorPosition", view.selectionStart)
            }
            pendingFieldId = view.id
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
                    saved = true
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
            rawFields.clear()
            renderField(etName, R.id.et_name, replaceRule.name)
            renderField(etGroup, R.id.et_group, replaceRule.group.orEmpty())
            renderField(etReplaceRule, R.id.et_replace_rule, replaceRule.pattern)
            cbUseRegex.isChecked = replaceRule.isRegex
            renderField(etReplaceTo, R.id.et_replace_to, replaceRule.replacement)
            cbScopeTitle.isChecked = replaceRule.scopeTitle
            cbScopeSource.isChecked = replaceRule.scopeSource
            cbScopeContent.isChecked = replaceRule.scopeContent
            renderField(etScope, R.id.et_scope, replaceRule.scope.orEmpty())
            renderField(etExcludeScope, R.id.et_exclude_scope, replaceRule.excludeScope.orEmpty())
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
        val source = viewModel.replaceRule ?: ReplaceRule()
        return source.copy(
            name = rawFields[R.id.et_name] ?: etName.text.toString(),
            group = (rawFields[R.id.et_group] ?: etGroup.text.toString()).ifBlank { null },
            pattern = rawFields[R.id.et_replace_rule] ?: etReplaceRule.text.toString(),
            isRegex = cbUseRegex.isChecked,
            replacement = rawFields[R.id.et_replace_to] ?: etReplaceTo.text.toString(),
            scopeTitle = cbScopeTitle.isChecked,
            scopeSource = cbScopeSource.isChecked,
            scopeContent = cbScopeContent.isChecked,
            scope = (rawFields[R.id.et_scope] ?: etScope.text.toString()).ifBlank { null },
            excludeScope = (rawFields[R.id.et_exclude_scope] ?: etExcludeScope.text.toString()).ifBlank { null },
            timeoutMillisecond = etTimeout.text.toString().toLongOrNull() ?: 3000L,
        )
    }

    private fun renderField(view: EditText, fieldId: Int, raw: String) {
        val unsafe = EditSafety.isTooLongForInline(raw) || EditSafety.isCombiningHeavy(raw)
        if (unsafe) rawFields[fieldId] = raw else rawFields.remove(fieldId)
        view.setText(if (EditSafety.isTooLongForInline(raw)) getString(R.string.large_text_placeholder, raw.length)
        else if (unsafe) getString(R.string.combining_text_placeholder) else raw)
        view.maxLines = if (unsafe) EditSafety.PREVIEW_LINES else Int.MAX_VALUE
        view.isFocusable = !unsafe
        view.isFocusableInTouchMode = !unsafe
        view.isCursorVisible = !unsafe
        view.isClickable = true
        if (unsafe) {
            view.setOnClickListener { pendingFieldId = fieldId; onFullEditClicked(fieldId) }
        } else {
            view.setOnClickListener(null)
        }
    }

    override fun finish() {
        val current = getReplaceRule()
        val baseline = originalRule
        val sample = ReplacePreview.normalizeSample(binding.etPreviewInput.text.toString())
        val changed = baseline != null && !sameRule(current, baseline) || sample != originalSample
        if (changed && !saved) {
            alert(R.string.exit) {
                setMessage(R.string.exit_no_save)
                positiveButton(R.string.yes)
                negativeButton(R.string.no) { super@ReplaceEditActivity.finish() }
            }
        } else super.finish()
    }

    private fun sameRule(a: ReplaceRule, b: ReplaceRule): Boolean =
        a.name == b.name && a.group == b.group && a.pattern == b.pattern && a.replacement == b.replacement &&
            a.scope == b.scope && a.scopeTitle == b.scopeTitle && a.scopeSource == b.scopeSource &&
            a.scopeContent == b.scopeContent && a.excludeScope == b.excludeScope && a.isEnabled == b.isEnabled &&
            a.isRegex == b.isRegex && a.timeoutMillisecond == b.timeoutMillisecond && a.order == b.order

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
