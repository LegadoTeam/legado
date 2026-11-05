package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import io.legado.app.R
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.constant.PreferKey
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setLayout

class ParagraphReadConfigDialog : BasePrefDialogFragment() {

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
        if (childFragmentManager.findFragmentByTag("paragraphPreferences") == null) {
            childFragmentManager.beginTransaction()
                .replace(view.id, ParagraphPreferenceFragment(), "paragraphPreferences").commit()
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as? ReadBookActivity)?.let { it.bottomDialog-- }
    }

    class ParagraphPreferenceFragment : PreferenceFragment(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.pref_config_paragraph_read, rootKey)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.setEdgeEffectColor(primaryColor)
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            when (key) {
                PreferKey.paragraphReadMode,
                PreferKey.paragraphReadControls,
                PreferKey.paragraphReadProgress,
                PreferKey.paragraphReadChapterSwipe -> {
                    (activity as? ReadBookActivity)?.updateParagraphMode()
                }
            }
        }
    }
}
