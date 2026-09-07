package io.legado.app.ui.highlight.edit

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightRule
import io.legado.app.databinding.DialogHighlightRuleEditBinding
import io.legado.app.help.HighlightColors
import io.legado.app.help.HighlightStyle
import io.legado.app.help.HighlightStyles
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.HighlightFillPreviewDrawable
import io.legado.app.ui.book.read.HighlightStyleDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HighlightRuleEditDialog : BaseDialogFragment(R.layout.dialog_highlight_rule_edit, true),
    HighlightStyleDialog.StyleHost,
    ColorPickerDialogListener {

    private val binding by viewBinding(DialogHighlightRuleEditBinding::bind)
    private var editingStyle = HighlightStyle()
    private var styleDialog: HighlightStyleDialog? = null
    private var rule: HighlightRule? = null
    private var isLoaded = false
    private var isSaving = false

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    @Suppress("DEPRECATION")
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.btnOk.isEnabled = false
        binding.btnStyle.setOnClickListener {
            HighlightStyleDialog().also {
                styleDialog = it
                showDialogFragment(it)
            }
        }
        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnOk.setOnClickListener { save() }
        (childFragmentManager.findFragmentByTag(COLOR_PICKER_TAG) as? ColorPickerDialog)
            ?.setColorPickerDialogListener(this)

        savedInstanceState?.getParcelable<HighlightRule>(STATE_RULE)?.let {
            rule = it
            isLoaded = true
            upView(it)
            return
        }
        val id = arguments?.getLong(ARG_ID, -1L) ?: -1L
        if (id > 0L) loadById(id) else fromArgs()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (isLoaded) (if (view == null) rule else getRule())?.let {
            outState.putParcelable(STATE_RULE, it)
        }
        super.onSaveInstanceState(outState)
    }

    private fun loadById(id: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = withContext(IO) { appDb.highlightRuleDao.findById(id) }
            if (loaded == null) {
                requireActivity().toastOnUi(R.string.highlight_rule_not_found)
                dismiss()
            } else {
                rule = loaded
                isLoaded = true
                upView(loaded)
            }
        }
    }

    private fun fromArgs() {
        val args = arguments ?: Bundle.EMPTY
        HighlightRule(
            name = args.getString(ARG_PATTERN).orEmpty(),
            pattern = args.getString(ARG_PATTERN).orEmpty(),
            isRegex = args.getBoolean(ARG_REGEX),
            scope = args.getString(ARG_SCOPE),
            style = args.getString(ARG_STYLE).orEmpty()
        ).also {
            rule = it
            isLoaded = true
            upView(it)
        }
    }

    private fun upView(rule: HighlightRule) = binding.run {
        etName.setText(rule.name)
        etPattern.setText(rule.pattern)
        cbUseRegex.isChecked = rule.isRegex
        cbApplyToBody.isChecked = rule.applyToBody
        cbApplyToTitle.isChecked = rule.applyToTitle
        etScope.setText(rule.scope)
        editingStyle = rule.styleObj()
        upPreview()
        btnOk.isEnabled = true
    }

    private fun getRule(): HighlightRule = binding.run {
        (rule ?: HighlightRule()).also {
            it.name = etName.text.toString()
            it.pattern = etPattern.text.toString()
            it.isRegex = cbUseRegex.isChecked
            it.applyToBody = cbApplyToBody.isChecked
            it.applyToTitle = cbApplyToTitle.isChecked
            it.scope = etScope.text.toString().ifBlank { null }
            it.applyStyle(editingStyle)
        }
    }

    private fun save() {
        if (!isLoaded || isSaving) return
        val savedRule = getRule()
        if (!savedRule.isValid()) {
            requireActivity().toastOnUi(
                getString(R.string.highlight_rule_invalid, savedRule.pattern)
            )
            return
        }
        isSaving = true
        lifecycleScope.launch {
            try {
                withContext(IO) {
                    if (savedRule.order == Int.MIN_VALUE) {
                        savedRule.order = appDb.highlightRuleDao.maxOrder + 1
                    }
                    appDb.highlightRuleDao.insert(savedRule)
                }
                ReadBook.upHighlightRules()
                dismiss()
            } finally {
                isSaving = false
            }
        }
    }

    private fun upPreview() {
        binding.tvStylePreview.background = if (editingStyle.fill != 0) {
            HighlightFillPreviewDrawable(editingStyle, binding.tvStylePreview.textSize)
        } else {
            null
        }
        binding.tvStylePreview.setTextColor(
            editingStyle.textColor.takeIf { it != 0 }
                ?: requireContext().getCompatColor(R.color.primaryText)
        )
    }

    override fun currentHighlightStyle(): HighlightStyle = editingStyle

    override fun onHighlightStyleChanged(style: HighlightStyle) {
        editingStyle = style
        upPreview()
    }

    override fun pickHighlightColor(dialogId: Int, initial: Int, withAlpha: Boolean) {
        createColorPickerDialog(dialogId, initial, withAlpha, this)
            .show(childFragmentManager, COLOR_PICKER_TAG)
    }

    override fun onColorSelected(dialogId: Int, color: Int) {
        editingStyle = HighlightStyleDialog.applyChannelColor(editingStyle, dialogId, color)
        refreshStyleDialog()
        upPreview()
    }

    override fun onDialogDismissed(dialogId: Int) = Unit

    private fun refreshStyleDialog() {
        styleDialog?.refresh()
        (childFragmentManager.findFragmentByTag(HighlightStyleDialog::class.simpleName)
                as? HighlightStyleDialog)?.refresh()
    }

    data class ColorPickerConfig(
        val dialogId: Int,
        val color: Int,
        val withAlpha: Boolean,
        val presets: IntArray
    )

    companion object {
        private const val ARG_ID = "id"
        private const val ARG_PATTERN = "pattern"
        private const val ARG_REGEX = "isRegex"
        private const val ARG_SCOPE = "scope"
        private const val ARG_STYLE = "style"
        private const val STATE_RULE = "rule"
        private const val COLOR_PICKER_TAG = "highlight-rule-color-picker"

        fun create(
            pattern: String,
            isRegex: Boolean = false,
            scope: String? = null,
            style: String? = null
        ) = HighlightRuleEditDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_PATTERN, pattern)
                putBoolean(ARG_REGEX, isRegex)
                putString(ARG_SCOPE, scope)
                putString(ARG_STYLE, initialStyle(style))
            }
        }

        fun edit(id: Long) = HighlightRuleEditDialog().apply {
            arguments = Bundle().apply { putLong(ARG_ID, id) }
        }

        fun initialStyle(style: String?): String =
            style ?: GSON.toJson(HighlightStyles.presets.first())

        fun colorPickerConfig(
            dialogId: Int,
            initial: Int,
            withAlpha: Boolean
        ): ColorPickerConfig {
            val presets = if (withAlpha) HighlightColors.bg else HighlightColors.text
            return ColorPickerConfig(
                dialogId,
                initial.takeIf { it != 0 } ?: presets.first(),
                withAlpha,
                presets
            )
        }

        fun createColorPickerDialog(
            dialogId: Int,
            initial: Int,
            withAlpha: Boolean,
            listener: ColorPickerDialogListener
        ): ColorPickerDialog {
            val config = colorPickerConfig(dialogId, initial, withAlpha)
            return ColorPickerDialog.newBuilder()
                .setColor(config.color)
                .setShowAlphaSlider(config.withAlpha)
                .setDialogType(ColorPickerDialog.TYPE_PRESETS)
                .setPresets(config.presets)
                .setDialogId(config.dialogId)
                .create()
                .also { it.setColorPickerDialogListener(listener) }
        }
    }
}
