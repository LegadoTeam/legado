package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.preference.Preference
import androidx.preference.SeekBarPreference
import io.legado.app.R
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.constant.PreferKey
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.readAloudControlWidth
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.setLayout

class ReadAloudControlsDialog : BasePrefDialogFragment() {
    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setBackgroundDrawableResource(R.color.transparent)
            setLayout(.9f, .8f)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        (activity as? ReadBookActivity)?.let { it.bottomDialog++ }
        return LinearLayout(requireContext()).apply {
            id = R.id.tag1
            setBackgroundColor(context.backgroundColor)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentByTag("controls") == null) {
            childFragmentManager.beginTransaction()
                .replace(view.id, ControlsPreferenceFragment(), "controls").commit()
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as? ReadBookActivity)?.let { it.bottomDialog-- }
    }

    class ControlsPreferenceFragment : PreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val width = readAloudControlWidth(requireContext().defaultSharedPreferences)
            setPreferencesFromResource(R.xml.pref_config_aloud_controls, rootKey)
            findPreference<SeekBarPreference>(PreferKey.readAloudControlsWidth)?.value = width
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            when (preference.key) {
                PreferKey.readAloudControlsRealtime -> {
                    (activity as? ReadBookActivity)?.showReadAloudControls()
                }
                "readAloudControlsReveal", "readAloudControlsReset" -> {
                    (activity as? ReadBookActivity)?.showReadAloudControls(
                        resetPosition = preference.key == "readAloudControlsReset",
                    )
                    return true
                }
            }
            return super.onPreferenceTreeClick(preference)
        }
    }
}
