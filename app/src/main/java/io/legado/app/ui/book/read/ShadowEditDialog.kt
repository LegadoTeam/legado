package io.legado.app.ui.book.read

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogHighlightShadowBinding
import io.legado.app.help.HighlightStyle.Shadow
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.util.Locale
import kotlin.math.roundToInt

class ShadowEditDialog : BaseDialogFragment(R.layout.dialog_highlight_shadow) {

    interface Callback {
        fun onShadowChanged(shadow: Shadow)
    }

    private val binding by viewBinding(DialogHighlightShadowBinding::bind)
    private val initialShadow: Shadow
        get() = Shadow(
            radius = arguments?.getFloat(ARG_RADIUS) ?: Shadow().radius,
            dx = arguments?.getFloat(ARG_DX) ?: Shadow().dx,
            dy = arguments?.getFloat(ARG_DY) ?: Shadow().dy,
            color = arguments?.getInt(ARG_COLOR) ?: Shadow().color
        )

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val shadow = initialShadow
        binding.dsbRadius.valueFormat = { formatHalf(it) }
        binding.dsbDx.valueFormat = { formatHalf(it - OFFSET_PROGRESS, signed = true) }
        binding.dsbDy.valueFormat = { formatHalf(it - OFFSET_PROGRESS, signed = true) }
        binding.dsbRadius.progress = progressOf(shadow.radius, 0f, 10f)
        binding.dsbDx.progress = progressOf(shadow.dx, -10f, 10f)
        binding.dsbDy.progress = progressOf(shadow.dy, -10f, 10f)

        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnOk.setOnClickListener {
            (parentFragment as? Callback)?.onShadowChanged(
                shadow.copy(
                    radius = binding.dsbRadius.progress / 2f,
                    dx = binding.dsbDx.progress / 2f - 10f,
                    dy = binding.dsbDy.progress / 2f - 10f
                )
            )
            dismiss()
        }
    }

    private fun progressOf(value: Float, min: Float, max: Float): Int {
        val safeValue = value.takeIf { it.isFinite() } ?: min
        return ((safeValue.coerceIn(min, max) - min) * 2).roundToInt()
    }

    private fun formatHalf(progress: Int, signed: Boolean = false): String {
        val format = if (signed) "%+.1f" else "%.1f"
        return String.format(Locale.getDefault(), format, progress / 2f)
    }

    companion object {
        private const val ARG_RADIUS = "radius"
        private const val ARG_DX = "dx"
        private const val ARG_DY = "dy"
        private const val ARG_COLOR = "color"
        private const val OFFSET_PROGRESS = 20

        fun show(fragmentManager: androidx.fragment.app.FragmentManager, shadow: Shadow) {
            ShadowEditDialog().apply {
                arguments = Bundle().apply {
                    putFloat(ARG_RADIUS, shadow.radius)
                    putFloat(ARG_DX, shadow.dx)
                    putFloat(ARG_DY, shadow.dy)
                    putInt(ARG_COLOR, shadow.color)
                }
            }.show(fragmentManager, ShadowEditDialog::class.simpleName)
        }
    }
}
