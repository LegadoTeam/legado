package io.legado.app.ui.config

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.model.BookCover
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefInt

class CoverFontConfigFragment : PreferenceFragment(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val sizes = listOf(PreferKey.coverTitleLargeSize, PreferKey.coverTitleSmallSize,
        PreferKey.coverAuthorLargeSize, PreferKey.coverAuthorSmallSize)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_cover_font)
        sizes.forEach(::updateSummary)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.cover_font_config)
    }

    override fun onDestroy() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (!isAdded) return
        if (key !in sizes && key != PreferKey.coverCustomFontSize) return
        key?.let(::updateSummary)
        BookCover.upDefaultCover()
        postEvent(EventBus.BOOKSHELF_REFRESH, "")
    }

    private fun updateSummary(key: String) {
        if (key in sizes) findPreference<Preference>(key)?.summary = "${getPrefInt(key, 100).coerceIn(50, 200)}%"
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key !in sizes) return super.onPreferenceTreeClick(preference)
        NumberPickerDialog(requireContext())
            .setTitle(preference.title.toString())
            .setMinValue(50).setMaxValue(200)
            .setValue(getPrefInt(preference.key, 100).coerceIn(50, 200))
            .setCustomButton(R.string.btn_default_s) { putPrefInt(preference.key, 100) }
            .show { putPrefInt(preference.key, it) }
        return true
    }
}
