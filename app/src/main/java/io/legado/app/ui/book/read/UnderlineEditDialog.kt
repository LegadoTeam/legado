package io.legado.app.ui.book.read

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogHighlightUnderlineBinding
import io.legado.app.help.HighlightStyle.Kind
import io.legado.app.help.HighlightStyle.Underline
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.util.Locale
import kotlin.math.roundToInt

class UnderlineEditDialog : BaseDialogFragment(R.layout.dialog_highlight_underline) {

    interface Callback {
        fun onUnderlineChanged(underline: Underline)
    }

    private val binding by viewBinding(DialogHighlightUnderlineBinding::bind)
    private val initialUnderline: Underline
        get() = Underline(
            kind = arguments?.getString(ARG_KIND)?.let { runCatching { Kind.valueOf(it) }.getOrNull() }
                ?: Underline().kind,
            color = arguments?.getInt(ARG_COLOR) ?: Underline().color,
            width = arguments?.getFloat(ARG_WIDTH) ?: Underline().width,
            distance = arguments?.getFloat(ARG_DISTANCE) ?: Underline().distance
        ).normalized()

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val underline = initialUnderline
        binding.dsbWidth.valueFormat = ::formatHalf
        binding.dsbDistance.valueFormat = ::formatHalf
        binding.dsbWidth.progress = progressOf(underline.width, Underline.MIN_WIDTH, Underline.MAX_WIDTH)
        binding.dsbDistance.progress = progressOf(
            underline.distance,
            Underline.MIN_DISTANCE,
            Underline.MAX_DISTANCE
        )

        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnOk.setOnClickListener {
            (parentFragment as? Callback)?.onUnderlineChanged(
                underline.copy(
                    width = binding.dsbWidth.progress / 2f,
                    distance = binding.dsbDistance.progress / 2f
                ).normalized()
            )
            dismiss()
        }
    }

    private fun progressOf(value: Float, min: Float, max: Float): Int {
        val safeValue = value.takeIf { it.isFinite() } ?: min
        return ((safeValue.coerceIn(min, max) - min) * 2).roundToInt()
    }

    private fun formatHalf(progress: Int): String {
        return String.format(Locale.getDefault(), "%.1f", progress / 2f)
    }

    companion object {
        private const val ARG_KIND = "kind"
        private const val ARG_COLOR = "color"
        private const val ARG_WIDTH = "width"
        private const val ARG_DISTANCE = "distance"

        fun show(fragmentManager: androidx.fragment.app.FragmentManager, underline: Underline) {
            UnderlineEditDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_KIND, underline.kind.name)
                    putInt(ARG_COLOR, underline.color)
                    putFloat(ARG_WIDTH, underline.width)
                    putFloat(ARG_DISTANCE, underline.distance)
                }
            }.show(fragmentManager, UnderlineEditDialog::class.simpleName)
        }
    }
}
