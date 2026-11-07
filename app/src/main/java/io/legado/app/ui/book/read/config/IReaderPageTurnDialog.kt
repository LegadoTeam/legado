package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.preference.Preference
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.normalizeIReaderPageTurnPreferences
import io.legado.app.lib.eink.IReaderDisplaySettings
import io.legado.app.lib.eink.IReaderPageTurnEffect
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.BaseReadBookActivity
import io.legado.app.utils.dpToPx
import io.legado.app.utils.sendToClip

/** Configures the iReader page-animation mode without a second enable/disable switch. */
class IReaderPageTurnDialog : BasePrefDialogFragment() {

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(R.color.background)
            decorView.setPadding(0, 0, 0, 0)
            attributes = attributes.apply {
                dimAmount = 0.0f
                gravity = Gravity.BOTTOM
            }
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 360.dpToPx())
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        (activity as? BaseReadBookActivity)?.let { it.bottomDialog++ }
        return LinearLayout(requireContext()).apply {
            id = R.id.tag1
            setBackgroundColor(context.bottomBackground)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentByTag("ireaderPageTurn") == null) {
            childFragmentManager.beginTransaction()
                .replace(view.id, EffectPreferences(), "ireaderPageTurn").commit()
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as? BaseReadBookActivity)?.let { it.bottomDialog-- }
    }

    class EffectPreferences : PreferenceFragment(), SharedPreferences.OnSharedPreferenceChangeListener {

        private var displaySettings: IReaderDisplaySettings.Snapshot? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferences?.let(::normalizeIReaderPageTurnPreferences)
            setPreferencesFromResource(R.xml.pref_config_ireader_page_turn, rootKey)
            findPreference<Preference>(PreferKey.iReaderPageTurnEffect)?.setOnPreferenceChangeListener { _, value ->
                val effect = (value as? String)?.toIntOrNull()
                    ?: return@setOnPreferenceChangeListener false
                effect in 1..2
            }
            updateStatus()
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
            displaySettings = IReaderDisplaySettings.read(requireContext())
            updateStatus()
        }

        override fun onPause() {
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
            if (key !in setOf(
                    PreferKey.iReaderPageTurnEffect, PreferKey.iReaderPageTurnSpeed,
                    PreferKey.iReaderPageTurnReverse, PreferKey.iReaderPageTurnReverseLandscape,
                    PreferKey.iReaderPageTurnRefreshInterval,
                )) return
            // These options are read for the next committed page; no layout/delegate reset is needed.
            updateStatus()
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            if (preference.key == "iReaderPageTurnDiagnostics") {
                val settings = IReaderDisplaySettings.read(requireContext()).also { displaySettings = it }
                // No book names, URLs or credentials are included in the report.
                requireContext().sendToClip(buildString {
                    appendLine("Legado iReader page-turn diagnostics")
                    appendLine("app=${BuildConfig.VERSION_NAME}")
                    appendLine("device=${Build.MANUFACTURER}/${Build.BRAND}/${Build.MODEL}")
                    appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
                    appendLine("firmware=${Build.DISPLAY}")
                    appendLine("capability=${IReaderPageTurnEffect.capability()}")
                    appendLine("failure=${IReaderPageTurnEffect.failureReason().orEmpty()}")
                    appendLine("eink=${AppConfig.isEInkMode} pagination=${ReadBook.pageAnim()}")
                    appendLine("effect=${AppConfig.iReaderPageTurnEffect} speed=${AppConfig.iReaderPageTurnSpeed}")
                    appendLine("reverse=${AppConfig.iReaderPageTurnReverse}")
                    appendLine("reverseLandscape=${AppConfig.iReaderPageTurnReverseLandscape}")
                    appendLine("refreshInterval=${AppConfig.iReaderPageTurnRefreshInterval}")
                    appendLine("display.readRefreshPattern=${settings.readRefreshPattern}")
                    appendLine("display.postMode=${settings.refreshMode}")
                    appendLine("display.waveform=${settings.waveform}")
                    appendLine("display.systemRefreshFrequency=${settings.systemRefreshFrequency}")
                    appendLine("display.packageRefreshFrequency=${settings.packageRefreshFrequency}")
                    appendLine("display.systemRefreshMode=${settings.systemRefreshMode}")
                    appendLine("display.packageRefreshMode=${settings.packageRefreshMode}")
                    appendLine("display.packageAnimationType=${settings.packageAnimationType}")
                    appendLine("display.packageAnimationSpeed=${settings.packageAnimationSpeed}")
                    appendLine("display.effectSupported=${settings.effectSupportedBySystemMode}")
                    appendLine("display.fullRefreshControlledBySystem=${settings.fullRefreshControlledBySystem}")
                    for ((key, failure) in settings.errors.toSortedMap()) {
                        appendLine("display.error.$key=$failure")
                    }
                })
                updateStatus()
                return true
            }
            return super.onPreferenceTreeClick(preference)
        }

        private fun updateStatus() {
            val capability = IReaderPageTurnEffect.capability()
            val available = capability == IReaderPageTurnEffect.Capability.AVAILABLE
            val status = when {
                capability == IReaderPageTurnEffect.Capability.FAILED -> getString(R.string.ireader_page_turn_failed)
                !available -> getString(R.string.ireader_page_turn_unavailable)
                ReadBook.pageAnim() != PageAnim.ireaderPageAnim -> getString(R.string.ireader_page_turn_not_selected)
                displaySettings?.effectSupportedBySystemMode == false ->
                    getString(R.string.ireader_page_turn_mode_unsupported)
                else -> null
            }
            findPreference<Preference>("iReaderPageTurnStatus")?.apply {
                isVisible = status != null
                summary = status
            }
            for (key in arrayOf(
                PreferKey.iReaderPageTurnEffect,
                PreferKey.iReaderPageTurnSpeed,
                PreferKey.iReaderPageTurnReverse,
                PreferKey.iReaderPageTurnReverseLandscape,
                PreferKey.iReaderPageTurnRefreshInterval,
            )) findPreference<Preference>(key)?.isEnabled = available
        }

    }
}
