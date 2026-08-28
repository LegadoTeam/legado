package io.legado.app.ui.book.read.config

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
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
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.ui.widget.text.AccentBgTextView
import io.legado.app.utils.dpToPx
import io.legado.app.utils.hexString
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding

class TipConfigDialog : BaseDialogFragment(R.layout.dialog_tip_config),
    FontSelectDialog.CallBack {

    companion object {
        const val TIP_COLOR = 7897
        const val TIP_DIVIDER_COLOR = 7898
        const val TITLE_NUMBER_COLOR = 7899
        const val TITLE_COLOR = 7900
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
            upTitleColor()
            upTitleNumberColor()
            upTvTipColor()
            upTvTipDividerColor()
        }
    }

    private fun initView() {
        if (ReadBookConfig.titleMode !in 0..3) {
            ReadBookConfig.titleMode = 0
        }
        binding.rgTitleMode.check(
            when (ReadBookConfig.titleMode) {
                1 -> R.id.rb_title_mode2
                2 -> R.id.rb_title_mode3
                3 -> R.id.rb_title_mode4
                else -> R.id.rb_title_mode1
            }
        )
        binding.dsbTitleSize.progress = ReadBookConfig.titleSize
        binding.dsbTitleLineSpacing.valueFormat = ::titleLineSpacingDisplayValue
        binding.dsbTitleLineSpacing.progress =
            titleLineSpacingToProgress(ReadBookConfig.titleLineSpacingExtra)
        upTitleFont()
        upTitleColor()
        binding.swSplitChapterTitle.isChecked = ReadBookConfig.splitChapterTitle
        binding.dsbTitleNumberSize.progress = ReadBookConfig.titleNumberSize
        binding.dsbTitleNumberSpacing.valueFormat = {
            titleNumberSpacingFromProgress(it).toString()
        }
        binding.dsbTitleNumberSpacing.progress =
            titleNumberSpacingToProgress(ReadBookConfig.titleNumberSpacing)
        upTitleNumberOptions()
        upTitleNumberColor()
        binding.dsbTitleTop.progress = ReadBookConfig.titleTopSpacing
        binding.dsbTitleBottom.progress = ReadBookConfig.titleBottomSpacing

        binding.tvHeaderShow.text =
            ReadTipConfig.getHeaderModes(requireContext())[ReadTipConfig.headerMode]
        binding.tvFooterShow.text =
            ReadTipConfig.getFooterModes(requireContext())[ReadTipConfig.footerMode]
        binding.dsbTipTextSize.valueFormat = {
            tipTextSizeFromProgress(it).toString()
        }
        binding.dsbTipTextSize.progress =
            tipTextSizeToProgress(ReadTipConfig.tipTextSize)

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

    private fun upTitleNumberOptions() {
        binding.llTitleNumberStyle.isVisible = ReadBookConfig.splitChapterTitle
    }

    private fun upTitleColor() {
        val color = ReadBookConfig.titleColor
        binding.tvTitleColor.text = if (color == 0) {
            ReadTipConfig.tipColorNames.first()
        } else {
            "#${color.hexString}"
        }
    }

    private fun upTitleFont() {
        binding.tvTitleFont.text = ReadBookConfig.titleFont.takeIf { it.isNotEmpty() }
            ?.let { path ->
                Uri.decode(path)
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
                    .ifBlank { path }
            }
            ?: getString(R.string.follow_text_font)
    }

    private fun upTitleNumberColor() {
        val color = ReadBookConfig.titleNumberColor
        binding.tvTitleNumberColor.text = if (color == 0) {
            ReadTipConfig.tipColorNames.first()
        } else {
            "#${color.hexString}"
        }
    }

    private fun initEvent() = binding.run {
        rgTitleMode.setOnCheckedChangeListener { _, checkedId ->
            ReadBookConfig.titleMode = when (checkedId) {
                R.id.rb_title_mode2 -> 1
                R.id.rb_title_mode3 -> 2
                R.id.rb_title_mode4 -> 3
                else -> 0
            }
            postEvent(EventBus.UP_CONFIG, arrayListOf(5))
        }
        dsbTitleSize.onChanged = {
            ReadBookConfig.titleSize = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
        dsbTitleLineSpacing.onChanged = {
            ReadBookConfig.titleLineSpacingExtra = titleLineSpacingFromProgress(it)
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
        llTitleFont.setOnClickListener {
            showDialogFragment<FontSelectDialog>()
        }
        llTitleColor.setOnClickListener {
            context?.selector(items = ReadTipConfig.tipColorNames) { _, i ->
                when (i) {
                    0 -> {
                        ReadBookConfig.titleColor = 0
                        upTitleColor()
                        postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
                    }

                    1 -> ColorPickerDialog.newBuilder()
                        .setColor(ReadBookConfig.titleTextColor)
                        .setShowAlphaSlider(false)
                        .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                        .setDialogId(TITLE_COLOR)
                        .show(requireActivity())
                }
            }
        }
        swSplitChapterTitle.setOnCheckedChangeListener { _, isChecked ->
            ReadBookConfig.splitChapterTitle = isChecked
            upTitleNumberOptions()
            postEvent(EventBus.UP_CONFIG, arrayListOf(5))
        }
        dsbTitleNumberSize.onChanged = {
            ReadBookConfig.titleNumberSize = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
        dsbTitleNumberSpacing.onChanged = {
            ReadBookConfig.titleNumberSpacing = titleNumberSpacingFromProgress(it)
            postEvent(EventBus.UP_CONFIG, arrayListOf(5))
        }
        llTitleNumberColor.setOnClickListener {
            context?.selector(items = ReadTipConfig.tipColorNames) { _, i ->
                when (i) {
                    0 -> {
                        ReadBookConfig.titleNumberColor = 0
                        upTitleNumberColor()
                        postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
                    }

                    1 -> ColorPickerDialog.newBuilder()
                        .setColor(ReadBookConfig.titleNumberTextColor)
                        .setShowAlphaSlider(false)
                        .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                        .setDialogId(TITLE_NUMBER_COLOR)
                        .show(requireActivity())
                }
            }
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
        dsbTipTextSize.onChanged = {
            ReadTipConfig.tipTextSize = tipTextSizeFromProgress(it)
            postEvent(EventBus.UP_CONFIG, arrayListOf(2))
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

    override val curFontPath: String
        get() = ReadBookConfig.titleFont

    override val selectSystemTypefaceOnDefault = false

    override fun selectFont(path: String) {
        if (path != ReadBookConfig.titleFont || path.isEmpty()) {
            ReadBookConfig.titleFont = path
            upTitleFont()
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
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

private const val TITLE_LINE_SPACING_MIN = -20
private const val TITLE_LINE_SPACING_MAX = 30

internal fun titleLineSpacingToProgress(spacing: Int): Int {
    return (spacing.coerceIn(TITLE_LINE_SPACING_MIN, TITLE_LINE_SPACING_MAX) -
        TITLE_LINE_SPACING_MIN)
}

internal fun titleLineSpacingFromProgress(progress: Int): Int {
    return (progress + TITLE_LINE_SPACING_MIN)
        .coerceIn(TITLE_LINE_SPACING_MIN, TITLE_LINE_SPACING_MAX)
}

internal fun titleLineSpacingDisplayValue(progress: Int): String {
    return (titleLineSpacingFromProgress(progress) / 10f).toString()
}

private const val TITLE_NUMBER_SPACING_MIN = -50
private const val TITLE_NUMBER_SPACING_MAX = 100

internal fun titleNumberSpacingToProgress(spacing: Int): Int {
    return spacing.coerceIn(TITLE_NUMBER_SPACING_MIN, TITLE_NUMBER_SPACING_MAX) -
        TITLE_NUMBER_SPACING_MIN
}

internal fun titleNumberSpacingFromProgress(progress: Int): Int {
    return (progress + TITLE_NUMBER_SPACING_MIN)
        .coerceIn(TITLE_NUMBER_SPACING_MIN, TITLE_NUMBER_SPACING_MAX)
}

internal fun tipTextSizeToProgress(textSize: Int): Int {
    return textSize.coerceIn(ReadTipConfig.minTextSize, ReadTipConfig.maxTextSize) -
        ReadTipConfig.minTextSize
}

internal fun tipTextSizeFromProgress(progress: Int): Int {
    return (progress + ReadTipConfig.minTextSize)
        .coerceIn(ReadTipConfig.minTextSize, ReadTipConfig.maxTextSize)
}
