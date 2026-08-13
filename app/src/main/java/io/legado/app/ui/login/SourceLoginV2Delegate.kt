package io.legado.app.ui.login

import android.os.CountDownTimer
import android.text.InputType
import android.view.LayoutInflater
import android.widget.TextView
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputLayout
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.databinding.DialogLoginBinding
import io.legado.app.databinding.ItemFilletTextBinding
import io.legado.app.databinding.ItemLoginLabelBinding
import io.legado.app.databinding.ItemLoginToggleBinding
import io.legado.app.databinding.ItemSourceEditBinding
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.view.ThemeSwitch
import io.legado.app.model.login.LoginUiV2
import io.legado.app.utils.GSON
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SourceLoginV2Delegate(
    private val fragment: SourceLoginDialog,
    private val binding: DialogLoginBinding,
    private val source: BaseSource,
) {

    private var stateJson = "{}"
    private var renderJob: Job? = null
    private var actionJob: Job? = null
    private var firstRender = true
    private val sessionValues = linkedMapOf<String, String>()
    private val fieldViews = linkedMapOf<String, ItemSourceEditBinding>()
    private val toggleViews = linkedMapOf<String, ThemeSwitch>()
    private val buttonViews = hashMapOf<String, TextView>()
    private val toggleActionViews = hashMapOf<String, ThemeSwitch>()
    private val buttonLabels = hashMapOf<String, String>()
    private val countdownLeft = hashMapOf<String, Int>()
    private val countdownTimers = hashMapOf<String, CountDownTimer>()

    private val scope get() = fragment.viewLifecycleOwner.lifecycleScope
    private val inflater: LayoutInflater get() = fragment.layoutInflater

    fun start() {
        render()
    }

    fun destroy() {
        renderJob?.cancel()
        actionJob?.cancel()
        countdownTimers.values.forEach(CountDownTimer::cancel)
        countdownTimers.clear()
        fieldViews.clear()
        toggleViews.clear()
        buttonViews.clear()
        toggleActionViews.clear()
        binding.rotateLoading.gone()
    }

    private fun render(
        candidateState: String = stateJson,
        errors: Map<String, String>? = null,
        restoreActionOnFailure: String? = null,
    ) {
        renderJob?.cancel()
        collectForm()
        renderJob = scope.launch {
            val showLoading = firstRender
            if (showLoading) binding.rotateLoading.visible()
            val result = withContext(IO) {
                runCatching {
                    runScriptWithContext {
                        val rows = LoginUiV2.parseRender(source.evalLoginUiV2(candidateState))
                        rows to source.getLoginInfoMap()
                    }
                }.onFailure { ensureActive() }
            }
            ensureActive()
            if (showLoading) {
                firstRender = false
                binding.rotateLoading.gone()
            }
            val rows = result.getOrNull()?.first
            if (rows == null) {
                restoreActionOnFailure?.let { setActionEnabled(it, true) }
                val error = result.exceptionOrNull()
                if (error == null) {
                    AppLog.put("登录UI v2 渲染结果格式错误")
                } else {
                    AppLog.put("登录UI v2 渲染出错", error)
                }
                if (binding.flexbox.childCount == 0) {
                    showRenderError()
                } else {
                    fragment.context?.toastOnUi(R.string.login_ui_v2_render_error)
                    errors?.let(::applyErrors)
                }
                return@launch
            }
            collectForm()
            stateJson = candidateState
            buildViews(rows, result.getOrThrow().second)
            errors?.let(::applyErrors)
        }
    }

    private fun showRenderError() {
        binding.flexbox.removeAllViews()
        fieldViews.clear()
        toggleViews.clear()
        buttonViews.clear()
        toggleActionViews.clear()
        ItemLoginLabelBinding.inflate(inflater, binding.root, false).let {
            binding.flexbox.addView(it.root)
            it.root.setText(R.string.login_ui_v2_render_error)
        }
    }

    private fun buildViews(rows: List<RowUi>, stored: Map<String, String>) {
        binding.flexbox.removeAllViews()
        fieldViews.clear()
        toggleViews.clear()
        buttonViews.clear()
        toggleActionViews.clear()
        buttonLabels.clear()
        rows.forEach { row ->
            when (row.type) {
                RowUi.Type.text -> addField(row, stored, password = false)
                RowUi.Type.password -> addField(row, stored, password = true)
                RowUi.Type.label -> addLabel(row)
                RowUi.Type.select -> addSelect(row, stored)
                RowUi.Type.toggle -> addToggle(row, stored)
                RowUi.Type.button -> addButton(row)
            }
        }
        countdownLeft.forEach { (action, left) ->
            if (left > 0) applyCountdown(action, left)
        }
    }

    private fun addField(row: RowUi, stored: Map<String, String>, password: Boolean) {
        ItemSourceEditBinding.inflate(inflater, binding.root, false).let { field ->
            binding.flexbox.addView(field.root)
            row.style().apply(field.root)
            field.textInputLayout.hint = row.name
            field.textInputLayout.placeholderText = row.hint
            if (password) {
                field.editText.inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                field.textInputLayout.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            }
            val key = requireNotNull(row.key)
            field.editText.setText(
                LoginUiV2.resolveFieldValue(row.value, sessionValues[key], stored[key])
            )
            fieldViews[key] = field
        }
    }

    private fun addLabel(row: RowUi) {
        ItemLoginLabelBinding.inflate(inflater, binding.root, false).let {
            binding.flexbox.addView(it.root)
            row.style().apply(it.root)
            it.root.text = row.name
        }
    }

    private fun addSelect(row: RowUi, stored: Map<String, String>) {
        val key = requireNotNull(row.key)
        val options = requireNotNull(row.options)
        ItemSourceEditBinding.inflate(inflater, binding.root, false).let { field ->
            binding.flexbox.addView(field.root)
            row.style().apply(field.root)
            field.textInputLayout.hint = row.name
            field.editText.isFocusable = false
            field.editText.isCursorVisible = false
            field.editText.keyListener = null
            field.textInputLayout.endIconMode = TextInputLayout.END_ICON_CUSTOM
            field.textInputLayout.setEndIconDrawable(R.drawable.ic_arrow_drop_down)
            val value = LoginUiV2.resolveFieldValue(row.value, sessionValues[key], stored[key])
                ?.takeIf { it in options }
                ?: options.first()
            field.editText.setText(value)
            val selectValue = {
                fragment.context?.selector(row.name, options) { _, item, _ ->
                    field.editText.setText(item)
                }
                Unit
            }
            field.root.setOnClickListener { selectValue() }
            field.editText.setOnClickListener { selectValue() }
            field.textInputLayout.setEndIconOnClickListener { selectValue() }
            fieldViews[key] = field
        }
    }

    private fun addButton(row: RowUi) {
        ItemFilletTextBinding.inflate(inflater, binding.root, false).let {
            binding.flexbox.addView(it.root)
            row.style().apply(it.root)
            it.textView.text = row.name
            it.textView.setPadding(16.dpToPx())
            val action = requireNotNull(row.action)
            buttonViews[action] = it.textView
            buttonLabels[action] = row.name
            it.root.setOnClickListener { dispatch(action, row.countdown) }
        }
    }

    private fun addToggle(row: RowUi, stored: Map<String, String>) {
        val key = requireNotNull(row.key)
        ItemLoginToggleBinding.inflate(inflater, binding.root, false).let {
            binding.flexbox.addView(it.root)
            row.style().apply(it.root)
            it.toggle.text = row.name
            it.toggle.isChecked = LoginUiV2.resolveFieldValue(
                row.value,
                sessionValues[key],
                stored[key],
            ) == "true"
            toggleViews[key] = it.toggle
            row.action?.let { action ->
                toggleActionViews[action] = it.toggle
                it.toggle.setOnCheckedChangeListener { _, _ -> dispatch(action, null) }
            }
        }
    }

    private fun collectForm(): Map<String, String> {
        val form = linkedMapOf<String, String>()
        fieldViews.forEach { (key, field) ->
            form[key] = field.editText.text?.toString().orEmpty()
        }
        toggleViews.forEach { (key, toggle) ->
            form[key] = toggle.isChecked.toString()
        }
        sessionValues.putAll(form)
        return form
    }

    private fun clearErrors() {
        fieldViews.values.forEach { it.textInputLayout.error = null }
    }

    private fun applyErrors(errors: Map<String, String>) {
        errors.forEach { (key, message) ->
            val field = fieldViews[key]
            if (field == null) {
                fragment.context?.toastOnUi(message)
            } else {
                field.textInputLayout.error = message
            }
        }
    }

    private fun dispatch(action: String, countdownSeconds: Int?) {
        if (renderJob?.isActive == true ||
            actionJob?.isActive == true ||
            countdownLeft.getOrDefault(action, 0) > 0
        ) {
            return
        }
        clearErrors()
        val formJson = GSON.toJson(collectForm())
        setActionEnabled(action, false)
        actionJob = scope.launch {
            val result = withContext(IO) {
                runCatching {
                    runScriptWithContext {
                        source.evalLoginActionV2(action, stateJson, formJson)
                    }
                }.onFailure { ensureActive() }
            }
            ensureActive()
            val error = result.exceptionOrNull()
            if (error != null) {
                setActionEnabled(action, true)
                AppLog.put("登录UI v2 动作 $action 出错", error)
                fragment.context?.toastOnUi(
                    fragment.getString(
                        R.string.login_ui_v2_action_error,
                        error.localizedMessage ?: error.toString(),
                    )
                )
                return@launch
            }
            val command = LoginUiV2.parseActionResult(result.getOrNull())
            if (command.malformed) {
                setActionEnabled(action, true)
                AppLog.put("登录UI v2 动作 $action 返回了无效命令")
                fragment.context?.toastOnUi(R.string.login_ui_v2_invalid_action)
                return@launch
            }
            command.unknownKeys.forEach {
                AppLog.put("登录UI v2 动作 $action 返回未知命令 $it,已忽略")
            }
            command.loginJson?.let { loginJson ->
                val saved = withContext(IO) { source.putLoginInfo(loginJson) }
                if (!saved) {
                    setActionEnabled(action, true)
                    fragment.context?.toastOnUi(R.string.login_ui_v2_save_error)
                    return@launch
                }
            }
            val errors = command.error.orEmpty()
            if (command.close) {
                fragment.dismissAllowingStateLoss()
                return@launch
            }
            val nextState = command.stateJson
            if (nextState == null) {
                setActionEnabled(action, true)
            }
            if (errors.isEmpty() && countdownSeconds != null && countdownSeconds > 0) {
                startCountdown(action, countdownSeconds)
            }
            if (nextState == null) {
                applyErrors(errors)
            } else {
                render(nextState, errors, action)
            }
        }
    }

    private fun setActionEnabled(action: String, enabled: Boolean) {
        toggleActionViews.values.forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.5f
        }
        buttonViews[action]?.let {
            val buttonEnabled = enabled && countdownLeft.getOrDefault(action, 0) <= 0
            it.isEnabled = buttonEnabled
            it.alpha = if (buttonEnabled) 1f else 0.5f
        }
    }

    private fun startCountdown(action: String, seconds: Int) {
        countdownTimers.remove(action)?.cancel()
        countdownLeft[action] = seconds
        applyCountdown(action, seconds)
        countdownTimers[action] = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val left = ((millisUntilFinished + 999) / 1000).toInt()
                countdownLeft[action] = left
                applyCountdown(action, left)
            }

            override fun onFinish() {
                countdownLeft.remove(action)
                countdownTimers.remove(action)
                buttonViews[action]?.let {
                    it.isEnabled = true
                    it.alpha = 1f
                    it.text = buttonLabels[action]
                }
            }
        }.apply { start() }
    }

    private fun applyCountdown(action: String, left: Int) {
        buttonViews[action]?.let {
            it.isEnabled = false
            it.alpha = 0.5f
            it.text = "${buttonLabels[action]} (${left}s)"
        }
    }
}
