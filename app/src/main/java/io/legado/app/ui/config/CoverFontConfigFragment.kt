package io.legado.app.ui.config

import android.content.SharedPreferences
import android.os.Bundle
import android.graphics.Typeface
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.model.BookCover
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.ui.font.installFontFile
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.FileDoc
import io.legado.app.utils.externalFiles
import io.legado.app.utils.openInputStream
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CoverFontConfigFragment : PreferenceFragment(), SharedPreferences.OnSharedPreferenceChangeListener,
    FontSelectDialog.CallBack {
    private val sizes = listOf(PreferKey.coverTitleLargeSize, PreferKey.coverTitleSmallSize,
        PreferKey.coverAuthorLargeSize, PreferKey.coverAuthorSmallSize)
    private val styleKeys = sizes + listOf(PreferKey.coverHorizontal, PreferKey.coverTitleAdaptive,
        PreferKey.coverKeepPunctuation, PreferKey.coverFont, PreferKey.coverCustomFontSize)
    private var fontJob: Job? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_cover_font)
        sizes.forEach(::updateSummary)
        updateSummary(PreferKey.coverFont)
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
        if (key !in styleKeys) return
        key?.let(::updateSummary)
        BookCover.upDefaultCover()
        findPreference<CoverPreviewPreference>("coverPreview")?.refresh()
        postEvent(EventBus.BOOKSHELF_REFRESH, "")
    }

    private fun updateSummary(key: String) {
        if (key in sizes) findPreference<Preference>(key)?.summary = "${getPrefInt(key, 100).coerceIn(50, 200)}%"
        if (key == PreferKey.coverFont) findPreference<Preference>(key)?.summary =
            curFontPath.takeIf { it.isNotBlank() }?.let { File(it).name } ?: getString(R.string.default_font)
    }

    override val curFontPath: String get() = getPrefString(PreferKey.coverFont).orEmpty()
    override val selectSystemTypefaceOnDefault = false

    override fun selectFont(path: String) {
        fontJob?.cancel()
        if (path.isEmpty()) {
            putPrefString(PreferKey.coverFont, "")
            return
        }
        val directory = File(requireContext().externalFiles, "font")
        fontJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val source = FileDoc.fromFile(path)
                    source.openInputStream().getOrThrow().use { input ->
                        installFontFile(input, source.name, directory) {
                            runCatching { Typeface.createFromFile(it) }.isSuccess
                        }.absolutePath
                    }
                }
            }
            result.onSuccess { putPrefString(PreferKey.coverFont, it) }
                .onFailure { requireContext().toastOnUi(it.localizedMessage) }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == PreferKey.coverFont) {
            showDialogFragment(FontSelectDialog())
            return true
        }
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
