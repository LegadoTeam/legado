package io.legado.app.ui.book.changesource

import android.content.DialogInterface
import android.text.InputType
import android.view.Menu
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.databinding.DialogMultipleEditTextBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.utils.visible

internal fun Menu.syncChangeSourceResultOptions() {
    findItem(R.id.menu_load_word_count)?.isChecked = AppConfig.changeSourceLoadWordCount
    findItem(R.id.menu_sort_respond_time)?.isChecked = AppConfig.changeSourceSortRespondTime
    findItem(R.id.menu_word_count_filter)?.isChecked =
        AppConfig.changeSourceWordCountFilterMode != ChangeSourceResultOptions.FILTER_OFF
}

internal fun Fragment.showChangeSourceWordCountFilter(
    onChanged: (reloadMeasurements: Boolean) -> Unit,
) {
    val modes: List<CharSequence> = listOf(
        getString(R.string.change_source_word_count_filter_off),
        getString(R.string.change_source_word_count_filter_absolute),
        getString(R.string.change_source_word_count_filter_relative),
    )
    requireContext().selector(R.string.change_source_word_count_filter, modes) { _, mode ->
        if (mode == ChangeSourceResultOptions.FILTER_OFF) {
            if (AppConfig.changeSourceWordCountFilterMode != mode) {
                AppConfig.changeSourceWordCountFilterMode = mode
                onChanged(false)
            }
        } else {
            showChangeSourceWordCountRange(mode, onChanged)
        }
    }
}

private fun Fragment.showChangeSourceWordCountRange(
    mode: Int,
    onChanged: (reloadMeasurements: Boolean) -> Unit,
) {
    val sameMode = AppConfig.changeSourceWordCountFilterMode == mode
    val defaultMinimum = if (mode == ChangeSourceResultOptions.FILTER_RELATIVE) 70 else 1000
    val defaultMaximum = if (mode == ChangeSourceResultOptions.FILTER_RELATIVE) 130 else 5000
    val minimum = if (sameMode) AppConfig.changeSourceWordCountFilterMin else defaultMinimum
    val maximum = if (sameMode) AppConfig.changeSourceWordCountFilterMax else defaultMaximum
    val binding = DialogMultipleEditTextBinding.inflate(layoutInflater).apply {
        layout1.hint = getString(R.string.change_source_word_count_minimum)
        layout2.hint = getString(R.string.change_source_word_count_maximum)
        layout2.visible()
        edit1.inputType = InputType.TYPE_CLASS_NUMBER
        edit2.inputType = InputType.TYPE_CLASS_NUMBER
        edit1.setText(minimum.toString())
        edit2.setText(maximum.toString())
        if (mode == ChangeSourceResultOptions.FILTER_RELATIVE) {
            layout1.suffixText = "%"
            layout2.suffixText = "%"
        }
    }
    val dialog = requireContext().alert(
        titleResource = R.string.change_source_word_count_filter,
    ) {
        setCustomView(binding.root)
        positiveButton(android.R.string.ok)
        cancelButton()
    }
    dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
        val newMinimum = binding.edit1.text?.toString()?.toIntOrNull()
        val newMaximum = binding.edit2.text?.toString()?.toIntOrNull()
        if (newMinimum == null || newMaximum == null ||
            newMinimum < 0 || newMaximum < newMinimum
        ) {
            binding.layout1.error = getString(R.string.error_scope_input)
            binding.layout2.error = getString(R.string.error_scope_input)
            return@setOnClickListener
        }
        val changed = mode != AppConfig.changeSourceWordCountFilterMode ||
                newMinimum != AppConfig.changeSourceWordCountFilterMin ||
                newMaximum != AppConfig.changeSourceWordCountFilterMax
        AppConfig.changeSourceWordCountFilterMin = newMinimum
        AppConfig.changeSourceWordCountFilterMax = newMaximum
        AppConfig.changeSourceWordCountFilterMode = mode
        if (changed) onChanged(true)
        dialog.dismiss()
    }
}
