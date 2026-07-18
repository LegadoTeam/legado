package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.indices
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.databinding.DialogReaderInfoTemplateBinding
import io.legado.app.databinding.DialogTipConfigBinding
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.help.config.ReaderInfoTemplate
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.ui.widget.text.AccentBgTextView
import io.legado.app.utils.checkByIndex
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getIndexById
import io.legado.app.utils.hexString
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding

class TipConfigDialog : BaseDialogFragment(R.layout.dialog_tip_config) {

    companion object {
        const val TIP_COLOR = 7897
        const val TIP_DIVIDER_COLOR = 7898
    }

    private val binding by viewBinding(DialogTipConfigBinding::bind)

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        initEvent()
        observeEvent<String>(EventBus.TIP_COLOR) {
            upTvTipColor()
            upTvTipDividerColor()
        }
    }

    private fun initView() {
        if (ReadBookConfig.titleMode !in binding.rgTitleMode.indices) {
            ReadBookConfig.titleMode = 0
        }
        binding.rgTitleMode.checkByIndex(ReadBookConfig.titleMode)
        binding.dsbTitleSize.progress = ReadBookConfig.titleSize
        binding.dsbTitleTop.progress = ReadBookConfig.titleTopSpacing
        binding.dsbTitleBottom.progress = ReadBookConfig.titleBottomSpacing

        binding.tvHeaderShow.text =
            ReadTipConfig.getHeaderModes(requireContext())[ReadTipConfig.headerMode]
        binding.tvFooterShow.text =
            ReadTipConfig.getFooterModes(requireContext())[ReadTipConfig.footerMode]

        initTipValues()
        upTvTipColor()
        upTvTipDividerColor()
    }

    private fun initTipValues() = binding.run {
        ReadTipConfig.run {
            tvHeaderLeft.text = effectiveTemplate(tipHeaderLeftTemplate, tipHeaderLeft)
            tvHeaderMiddle.text = effectiveTemplate(tipHeaderMiddleTemplate, tipHeaderMiddle)
            tvHeaderRight.text = effectiveTemplate(tipHeaderRightTemplate, tipHeaderRight)
            tvFooterLeft.text = effectiveTemplate(tipFooterLeftTemplate, tipFooterLeft)
            tvFooterMiddle.text = effectiveTemplate(tipFooterMiddleTemplate, tipFooterMiddle)
            tvFooterRight.text = effectiveTemplate(tipFooterRightTemplate, tipFooterRight)
        }
    }

    private fun upTvTipColor() {
        val tipColorNames = ReadTipConfig.tipColorNames
        val tipColor = ReadTipConfig.tipColor
        binding.tvTipColor.text = if (tipColor == 0) {
            tipColorNames.first()
        } else {
            "#${tipColor.hexString}"
        }
    }

    private fun upTvTipDividerColor() {
        val tipDividerColorNames = ReadTipConfig.tipDividerColorNames
        val tipDividerColor = ReadTipConfig.tipDividerColor
        binding.tvTipDividerColor.text = when (tipDividerColor) {
            -1, 0 -> tipDividerColorNames[tipDividerColor + 1]
            else -> "#${tipDividerColor.hexString}"
        }
    }

    private fun initEvent() = binding.run {
        rgTitleMode.setOnCheckedChangeListener { _, checkedId ->
            ReadBookConfig.titleMode = rgTitleMode.getIndexById(checkedId)
            postEvent(EventBus.UP_CONFIG, arrayListOf(5))
        }
        dsbTitleSize.onChanged = {
            ReadBookConfig.titleSize = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
        dsbTitleTop.onChanged = {
            ReadBookConfig.titleTopSpacing = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
        dsbTitleBottom.onChanged = {
            ReadBookConfig.titleBottomSpacing = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
        llHeaderShow.setOnClickListener {
            val headerModes = ReadTipConfig.getHeaderModes(requireContext())
            context?.selector(items = headerModes.values.toList()) { _, i ->
                ReadTipConfig.headerMode = headerModes.keys.toList()[i]
                tvHeaderShow.text = headerModes[ReadTipConfig.headerMode]
                postEvent(EventBus.UP_CONFIG, arrayListOf(2))
            }
        }
        llFooterShow.setOnClickListener {
            val footerModes = ReadTipConfig.getFooterModes(requireContext())
            context?.selector(items = footerModes.values.toList()) { _, i ->
                ReadTipConfig.footerMode = footerModes.keys.toList()[i]
                tvFooterShow.text = footerModes[ReadTipConfig.footerMode]
                postEvent(EventBus.UP_CONFIG, arrayListOf(2))
            }
        }
        llHeaderLeft.setOnClickListener {
            ReadTipConfig.run {
                editTemplate(
                    title = getString(R.string.reader_info_template),
                    current = effectiveTemplate(tipHeaderLeftTemplate, tipHeaderLeft),
                ) { tipHeaderLeftTemplate = it }
            }
        }
        llHeaderMiddle.setOnClickListener {
            ReadTipConfig.run {
                editTemplate(
                    title = getString(R.string.reader_info_template),
                    current = effectiveTemplate(tipHeaderMiddleTemplate, tipHeaderMiddle),
                ) { tipHeaderMiddleTemplate = it }
            }
        }
        llHeaderRight.setOnClickListener {
            ReadTipConfig.run {
                editTemplate(
                    title = getString(R.string.reader_info_template),
                    current = effectiveTemplate(tipHeaderRightTemplate, tipHeaderRight),
                ) { tipHeaderRightTemplate = it }
            }
        }
        llFooterLeft.setOnClickListener {
            ReadTipConfig.run {
                editTemplate(
                    title = getString(R.string.reader_info_template),
                    current = effectiveTemplate(tipFooterLeftTemplate, tipFooterLeft),
                ) { tipFooterLeftTemplate = it }
            }
        }
        llFooterMiddle.setOnClickListener {
            ReadTipConfig.run {
                editTemplate(
                    title = getString(R.string.reader_info_template),
                    current = effectiveTemplate(tipFooterMiddleTemplate, tipFooterMiddle),
                ) { tipFooterMiddleTemplate = it }
            }
        }
        llFooterRight.setOnClickListener {
            ReadTipConfig.run {
                editTemplate(
                    title = getString(R.string.reader_info_template),
                    current = effectiveTemplate(tipFooterRightTemplate, tipFooterRight),
                ) { tipFooterRightTemplate = it }
            }
        }
        llTipColor.setOnClickListener {
            context?.selector(items = ReadTipConfig.tipColorNames) { _, i ->
                when (i) {
                    0 -> {
                        ReadTipConfig.tipColor = 0
                        upTvTipColor()
                        postEvent(EventBus.UP_CONFIG, arrayListOf(2))
                    }

                    1 -> ColorPickerDialog.newBuilder()
                        .setShowAlphaSlider(false)
                        .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                        .setDialogId(TIP_COLOR)
                        .show(requireActivity())
                }
            }
        }
        llTipDividerColor.setOnClickListener {
            context?.selector(items = ReadTipConfig.tipDividerColorNames) { _, i ->
                when (i) {
                    0, 1 -> {
                        ReadTipConfig.tipDividerColor = i - 1
                        upTvTipDividerColor()
                        postEvent(EventBus.UP_CONFIG, arrayListOf(2))
                    }

                    2 -> ColorPickerDialog.newBuilder()
                        .setShowAlphaSlider(false)
                        .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                        .setDialogId(TIP_DIVIDER_COLOR)
                        .show(requireActivity())
                }
            }
        }
    }

    private fun editTemplate(
        title: String,
        current: String,
        save: (String) -> Unit,
    ) {
        val dialogBinding = DialogReaderInfoTemplateBinding.inflate(layoutInflater)
        dialogBinding.editTemplate.setText(current)
        dialogBinding.editTemplate.setSelection(current.length)
        ReaderInfoTemplate.placeholders.forEach { placeholder ->
            val placeholderView = AccentBgTextView(requireContext()).apply {
                text = placeholder
                setRadius(4)
                includeFontPadding = false
                setPadding(12.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
                setOnClickListener {
                    val edit = dialogBinding.editTemplate
                    val editable = edit.editableText
                    val start = minOf(edit.selectionStart, edit.selectionEnd)
                        .coerceIn(0, editable.length)
                    val end = maxOf(edit.selectionStart, edit.selectionEnd)
                        .coerceIn(0, editable.length)
                    editable.replace(start, end, placeholder)
                }
            }
            dialogBinding.chipPlaceholders.addView(placeholderView)
        }
        alert(title) {
            customView { dialogBinding.root }
            okButton {
                save(dialogBinding.editTemplate.editableText.toString())
                initTipValues()
                postEvent(EventBus.UP_CONFIG, arrayListOf(2, 6))
            }
            cancelButton()
        }
    }

}
