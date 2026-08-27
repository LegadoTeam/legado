package io.legado.app.ui.book.read

import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.legado.app.R
import io.legado.app.databinding.DialogHighlightStyleBinding
import io.legado.app.databinding.ItemHighlightChannelBinding
import io.legado.app.help.HighlightStyle
import io.legado.app.help.HighlightStyle.Deco
import io.legado.app.help.HighlightStyle.FillShape
import io.legado.app.help.HighlightStyle.Kind
import io.legado.app.help.HighlightStyle.Shadow
import io.legado.app.help.HighlightStyle.Underline
import io.legado.app.help.HighlightStyles
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.dpToPx
import io.legado.app.utils.showDialogFragment

class HighlightStyleDialog : BottomSheetDialogFragment(),
    ShadowEditDialog.Callback,
    UnderlineEditDialog.Callback,
    FontSelectDialog.CallBack {

    interface StyleHost {
        fun currentHighlightStyle(): HighlightStyle

        fun onHighlightStyleChanged(style: HighlightStyle)

        fun pickHighlightColor(dialogId: Int, initial: Int, withAlpha: Boolean)
    }

    private var _binding: DialogHighlightStyleBinding? = null
    private val binding get() = _binding!!
    private val styleHost get() = resolveStyleHost(parentFragment, activity)
    private val rows = arrayListOf<ItemHighlightChannelBinding>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogHighlightStyleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (styleHost == null) {
            dismiss()
            return
        }
        buildPresets()
        buildChannels()
        binding.llHighlightFont.setOnClickListener {
            showDialogFragment<FontSelectDialog>()
        }
        refresh()
    }

    override fun onDestroyView() {
        rows.clear()
        _binding = null
        super.onDestroyView()
    }

    private fun currentStyle(): HighlightStyle {
        return styleHost?.currentHighlightStyle() ?: HighlightStyle()
    }

    private fun apply(style: HighlightStyle) {
        styleHost?.onHighlightStyleChanged(style)
        refresh()
    }

    private fun buildPresets() {
        HighlightStyles.presets.forEach { preset ->
            binding.flPresets.addView(TextView(requireContext()).apply {
                text = "\u25CF"
                textSize = 20f
                setPadding(18.dpToPx(), 8.dpToPx(), 18.dpToPx(), 8.dpToPx())
                setTextColor(preset.previewColor())
                setOnClickListener { apply(preset) }
            })
        }
    }

    private fun HighlightStyle.previewColor(): Int {
        return fill.takeIf { it != 0 }
            ?: textColor.takeIf { it != 0 }
            ?: underline?.color?.takeIf { it != 0 }
            ?: strike?.color?.takeIf { it != 0 }
            ?: box?.color?.takeIf { it != 0 }
            ?: emphasis?.color?.takeIf { it != 0 }
            ?: shadow?.color?.takeIf { it != 0 }
            ?: DEFAULT_SWATCH_COLOR
    }

    private data class Channel(
        val labelRes: Int,
        val dialogId: Int,
        val withAlpha: Boolean,
        val isEnabled: (HighlightStyle) -> Boolean,
        val color: (HighlightStyle) -> Int,
        val toggle: (HighlightStyle, Boolean) -> HighlightStyle,
        val extra: ((HighlightStyle) -> String)? = null,
        val changeExtra: ((HighlightStyle) -> HighlightStyle)? = null
    )

    private val channels by lazy {
        listOf(
            Channel(
                R.string.highlight_bg_color,
                HL_FILL,
                true,
                { it.fill != 0 },
                { it.fill },
                { style, enabled ->
                    style.copy(
                        fill = if (enabled) {
                            style.fill.takeIf { it != 0 } ?: DEFAULT_FILL_COLOR
                        } else {
                            0
                        }
                    )
                },
                extra = { style -> fillShapeLabel(style.resolvedFillShape) },
                changeExtra = { style ->
                    style.copy(fillShape = nextFillShape(style.resolvedFillShape))
                }
            ),
            Channel(
                R.string.highlight_text_color,
                HL_TEXT,
                false,
                { it.textColor != 0 },
                { it.textColor },
                { style, enabled ->
                    style.copy(
                        textColor = if (enabled) {
                            style.textColor.takeIf { it != 0 } ?: DEFAULT_TEXT_COLOR
                        } else {
                            0
                        }
                    )
                }
            ),
            Channel(
                R.string.highlight_bold,
                NO_COLOR,
                false,
                { it.bold },
                { 0 },
                { style, enabled -> style.copy(bold = enabled) }
            ),
            Channel(
                R.string.highlight_italic,
                NO_COLOR,
                false,
                { it.italic },
                { 0 },
                { style, enabled -> style.copy(italic = enabled) }
            ),
            Channel(
                R.string.highlight_underline,
                HL_UNDERLINE,
                false,
                { it.underline != null },
                { it.underline?.color ?: 0 },
                { style, enabled ->
                    style.copy(underline = if (enabled) style.underline ?: Underline() else null)
                },
                extra = { style -> underlineLabel(style.underline?.kind) },
                changeExtra = { style ->
                    val underline = style.underline ?: Underline()
                    style.copy(underline = underline.copy(kind = nextKind(underline.kind)))
                }
            ),
            Channel(
                R.string.highlight_strike,
                HL_STRIKE,
                false,
                { it.strike != null },
                { it.strike?.color ?: 0 },
                { style, enabled ->
                    style.copy(strike = if (enabled) style.strike ?: Deco() else null)
                }
            ),
            Channel(
                R.string.highlight_box,
                HL_BOX,
                false,
                { it.box != null },
                { it.box?.color ?: 0 },
                { style, enabled ->
                    style.copy(box = if (enabled) style.box ?: Deco() else null)
                }
            ),
            Channel(
                R.string.highlight_emphasis,
                HL_EMPHASIS,
                false,
                { it.emphasis != null },
                { it.emphasis?.color ?: 0 },
                { style, enabled ->
                    style.copy(emphasis = if (enabled) style.emphasis ?: Deco() else null)
                }
            ),
            Channel(
                R.string.highlight_shadow,
                HL_SHADOW,
                true,
                { it.shadow != null },
                { it.shadow?.color ?: 0 },
                { style, enabled ->
                    style.copy(shadow = if (enabled) style.shadow ?: Shadow() else null)
                },
                extra = { style -> shadowLabel(style.shadow ?: Shadow()) }
            )
        )
    }

    private fun buildChannels() {
        channels.forEach { channel ->
            val row = ItemHighlightChannelBinding.inflate(
                layoutInflater,
                binding.llChannels,
                false
            )
            row.cbChannel.setText(channel.labelRes)
            row.cbChannel.setOnClickListener {
                val previousStyle = currentStyle()
                val newStyle = channel.toggle(previousStyle, row.cbChannel.isChecked)
                apply(newStyle)
                if (channel.labelRes == R.string.highlight_shadow &&
                    shouldOpenShadowEditor(previousStyle, newStyle)
                ) {
                    newStyle.shadow?.let { ShadowEditDialog.show(childFragmentManager, it) }
                }
            }
            row.vSwatch.setOnClickListener {
                if (channel.dialogId != NO_COLOR) {
                    styleHost?.pickHighlightColor(
                        channel.dialogId,
                        channel.color(currentStyle()),
                        channel.withAlpha
                    )
                }
            }
            row.vSwatch.contentDescription = getString(channel.labelRes)
            row.tvExtra.setOnClickListener {
                if (channel.labelRes == R.string.highlight_shadow) {
                    currentStyle().shadow?.let { ShadowEditDialog.show(childFragmentManager, it) }
                } else {
                    channel.changeExtra?.let { apply(it(currentStyle())) }
                }
            }
            row.tvTune.setOnClickListener {
                if (channel.labelRes == R.string.highlight_underline) {
                    currentStyle().underline?.let {
                        UnderlineEditDialog.show(childFragmentManager, it)
                    }
                }
            }
            binding.llChannels.addView(row.root)
            rows.add(row)
        }
    }

    fun refresh() {
        if (_binding == null) return
        val style = currentStyle()
        channels.forEachIndexed { index, channel ->
            val row = rows[index]
            val enabled = channel.isEnabled(style)
            row.cbChannel.isChecked = enabled
            row.vSwatch.visibility = if (channel.dialogId != NO_COLOR && enabled) {
                View.VISIBLE
            } else {
                View.GONE
            }
            if (channel.dialogId != NO_COLOR && enabled) {
                val color = channel.color(style).takeIf { it != 0 } ?: DEFAULT_SWATCH_COLOR
                row.vSwatchColor.backgroundTintList = ColorStateList.valueOf(color)
            }
            val extra = channel.extra?.invoke(style)
            row.tvExtra.visibility = if (extra != null && enabled) View.VISIBLE else View.GONE
            row.tvExtra.text = extra.orEmpty()
            val tuneVisible = channel.labelRes == R.string.highlight_underline && enabled
            row.tvTune.visibility = if (tuneVisible) View.VISIBLE else View.GONE
            if (tuneVisible) row.tvTune.text = getString(R.string.highlight_underline_adjust)
        }
        val fontPath = style.resolvedFontPath
        binding.tvHighlightFontValue.text = if (fontPath.isEmpty()) {
            getString(R.string.default_font)
        } else {
            Uri.decode(fontPath)
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .ifBlank { fontPath }
        }
    }

    override val curFontPath: String
        get() = currentStyle().resolvedFontPath

    override val selectSystemTypefaceOnDefault = false

    override fun selectFont(path: String) {
        ChapterProvider.invalidateHighlightTypeface(path)
        apply(currentStyle().copy(fontPath = path))
    }

    private fun underlineLabel(kind: Kind?): String = when (kind) {
        Kind.WAVY -> getString(R.string.highlight_underline_wavy)
        Kind.DASHED -> getString(R.string.highlight_underline_dashed)
        Kind.DOTTED -> getString(R.string.highlight_underline_dotted)
        Kind.DOUBLE -> getString(R.string.highlight_underline_double)
        else -> getString(R.string.highlight_underline_solid)
    }

    private fun nextKind(kind: Kind): Kind {
        val kinds = Kind.entries
        return kinds[(kinds.indexOf(kind) + 1) % kinds.size]
    }

    private fun fillShapeLabel(shape: FillShape): String = when (shape) {
        FillShape.RECTANGLE -> getString(R.string.highlight_fill_rectangle)
        FillShape.ROUNDED -> getString(R.string.highlight_fill_rounded)
        FillShape.MARKER -> getString(R.string.highlight_fill_marker)
        FillShape.HALF -> getString(R.string.highlight_fill_half)
        FillShape.BASELINE -> getString(R.string.highlight_fill_baseline)
        FillShape.PILL -> getString(R.string.highlight_fill_pill)
    }

    private fun nextFillShape(shape: FillShape): FillShape {
        val shapes = FillShape.entries
        return shapes[(shapes.indexOf(shape) + 1) % shapes.size]
    }

    private fun shadowLabel(shadow: Shadow): String =
        getString(R.string.highlight_shadow_values, shadow.radius, shadow.dx, shadow.dy)

    override fun onShadowChanged(shadow: Shadow) {
        apply(currentStyle().copy(shadow = shadow))
    }

    override fun onUnderlineChanged(underline: Underline) {
        apply(currentStyle().copy(underline = underline))
    }

    companion object {
        const val HL_FILL = 8101
        const val HL_TEXT = 8102
        const val HL_UNDERLINE = 8103
        const val HL_STRIKE = 8104
        const val HL_BOX = 8105
        const val HL_EMPHASIS = 8106
        const val HL_SHADOW = 8107

        private const val NO_COLOR = -1
        private val DEFAULT_FILL_COLOR = 0x80FFF176.toInt()
        private val DEFAULT_TEXT_COLOR = 0xFFE53935.toInt()
        private val DEFAULT_SWATCH_COLOR = 0xFF888888.toInt()

        fun resolveStyleHost(parent: Any?, activity: Any?): StyleHost? {
            return (parent as? StyleHost) ?: (activity as? StyleHost)
        }

        fun applyChannelColor(
            style: HighlightStyle,
            dialogId: Int,
            color: Int
        ): HighlightStyle = when (dialogId) {
            HL_FILL -> style.copy(fill = color)
            HL_TEXT -> style.copy(textColor = color)
            HL_UNDERLINE -> style.copy(
                underline = (style.underline ?: Underline()).copy(color = color)
            )
            HL_STRIKE -> style.copy(strike = Deco(color))
            HL_BOX -> style.copy(box = Deco(color))
            HL_EMPHASIS -> style.copy(emphasis = Deco(color))
            HL_SHADOW -> style.copy(
                shadow = (style.shadow ?: Shadow()).copy(color = color)
            )
            else -> style
        }

        internal fun shouldOpenShadowEditor(
            previousStyle: HighlightStyle,
            newStyle: HighlightStyle
        ): Boolean = previousStyle.shadow == null && newStyle.shadow != null
    }
}
