package io.legado.app.ui.code.config

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import io.github.rosemoe.sora.widget.CodeEditor.FLAG_DRAW_LINE_SEPARATOR
import io.github.rosemoe.sora.widget.CodeEditor.FLAG_DRAW_WHITESPACE_FOR_EMPTY_LINE
import io.github.rosemoe.sora.widget.CodeEditor.FLAG_DRAW_WHITESPACE_INNER
import io.github.rosemoe.sora.widget.CodeEditor.FLAG_DRAW_WHITESPACE_IN_SELECTION
import io.github.rosemoe.sora.widget.CodeEditor.FLAG_DRAW_WHITESPACE_LEADING
import io.github.rosemoe.sora.widget.CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogEditSettingsBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.init.appCtx

class SettingsDialog(private val callBack: CallBack) :
    BaseDialogFragment(R.layout.dialog_edit_settings) {
    companion object {
        private val fontSizeStr by lazy { appCtx.getString(R.string.font_size) + " " }
    }
    private val binding by viewBinding(DialogEditSettingsBinding::bind)
    private val editNonPrintable = AppConfig.editNonPrintable

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initData()
        initView()
    }


    @SuppressLint("SetTextI18n")
    private fun initData() {
        binding.run {
            tvFontSize.text = fontSizeStr + AppConfig.editFontScale
            cbAutoComplete.isChecked = AppConfig.editAutoComplete
            FLAGDRAWWHITESPACELEADING.isChecked = editNonPrintable and FLAG_DRAW_WHITESPACE_LEADING != 0
            FLAGDRAWWHITESPACEINNER.isChecked = editNonPrintable and FLAG_DRAW_WHITESPACE_INNER != 0
            FLAGDRAWWHITESPACETRAILING.isChecked = editNonPrintable and FLAG_DRAW_WHITESPACE_TRAILING != 0
            FLAGDRAWWHITESPACEFOREMPTYLINE.isChecked = editNonPrintable and FLAG_DRAW_WHITESPACE_FOR_EMPTY_LINE != 0
            FLAGDRAWLINESEPARATOR.isChecked = editNonPrintable and FLAG_DRAW_LINE_SEPARATOR != 0
            FLAGDRAWWHITESPACEINSELECTION.isChecked = editNonPrintable and FLAG_DRAW_WHITESPACE_IN_SELECTION != 0
        }
    }

    @SuppressLint("SetTextI18n")
    private fun initView() {
        binding.run {
            tvFontSize.setOnClickListener {
                NumberPickerDialog(requireContext())
                    .setTitle(getString(R.string.font_scale))
                    .setMaxValue(36)
                    .setMinValue(9)
                    .setValue(AppConfig.editFontScale)
                    .setCustomButton((R.string.btn_default_s)) {
                        putPrefInt(PreferKey.editFontScale, 16)
                        callBack.upEdit(fontSize = 16)
                        tvFontSize.text = fontSizeStr + "16"
                    }
                    .show {
                        putPrefInt(PreferKey.editFontScale, it)
                        callBack.upEdit(fontSize = it)
                        tvFontSize.text = fontSizeStr + it
                    }
            }
            cbAutoComplete.setOnCheckedChangeListener { _, isChecked ->
                putPrefBoolean(PreferKey.editAutoComplete, isChecked)
                callBack.upEdit(autoComplete = isChecked)
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        binding.run {
            var editNonPrintable = 0
            if (FLAGDRAWWHITESPACELEADING.isChecked) {
                editNonPrintable = editNonPrintable or FLAG_DRAW_WHITESPACE_LEADING
            }
            if (FLAGDRAWWHITESPACEINNER.isChecked) {
                editNonPrintable = editNonPrintable or FLAG_DRAW_WHITESPACE_INNER
            }
            if (FLAGDRAWWHITESPACETRAILING.isChecked) {
                editNonPrintable = editNonPrintable or FLAG_DRAW_WHITESPACE_TRAILING
            }
            if (FLAGDRAWWHITESPACEFOREMPTYLINE.isChecked) {
                editNonPrintable = editNonPrintable or FLAG_DRAW_WHITESPACE_FOR_EMPTY_LINE
            }
            if (FLAGDRAWLINESEPARATOR.isChecked) {
                editNonPrintable = editNonPrintable or FLAG_DRAW_LINE_SEPARATOR
            }
            if (FLAGDRAWWHITESPACEINSELECTION.isChecked) {
                editNonPrintable = editNonPrintable or FLAG_DRAW_WHITESPACE_IN_SELECTION
            }
            if (editNonPrintable != this@SettingsDialog.editNonPrintable) {
                putPrefInt(PreferKey.editNonPrintable, editNonPrintable)
                callBack.upEdit(editNonPrintable = editNonPrintable)
            }
        }
    }

    interface CallBack {
        fun upEdit(fontSize: Int? = null, autoComplete: Boolean? = null, autoWarp: Boolean? = null, editNonPrintable: Int? = null)
    }

}