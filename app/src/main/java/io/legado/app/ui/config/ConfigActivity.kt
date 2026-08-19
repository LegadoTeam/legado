package io.legado.app.ui.config

import android.os.Bundle
import android.view.Menu
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ActivityConfigBinding
import io.legado.app.lib.dialogs.selector
import io.legado.app.utils.observeEvent
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class ConfigActivity : VMBaseActivity<ActivityConfigBinding, ConfigViewModel>() {

    override val binding by viewBinding(ActivityConfigBinding::inflate)
    override val viewModel by viewModels<ConfigViewModel>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        when (val configTag = intent.getStringExtra("configTag")) {
            ConfigTag.OTHER_CONFIG -> replaceFragment<OtherConfigFragment>(configTag)
            ConfigTag.THEME_CONFIG -> replaceFragment<ThemeConfigFragment>(configTag)
            ConfigTag.BACKUP_CONFIG -> replaceFragment<BackupConfigFragment>(configTag)
            ConfigTag.COVER_CONFIG -> replaceFragment<CoverConfigFragment>(configTag)
            ConfigTag.WELCOME_CONFIG -> replaceFragment<WelcomeConfigFragment>(configTag)
            else -> finish()
        }
    }

    override fun setTitle(resId: Int) {
        super.setTitle(resId)
        binding.titleBar.setTitle(resId)
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.search_view, menu)
        (menu.findItem(R.id.menu_search).actionView as SearchView).apply {
            queryHint = getString(R.string.search)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    showPreferenceSearchResults(query.orEmpty(), this@apply)
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean = false
            })
        }
        return super.onCompatCreateOptionsMenu(menu)
    }

    inline fun <reified T : Fragment> replaceFragment(configTag: String) {
        intent.putExtra("configTag", configTag)
        @Suppress("DEPRECATION")
        val configFragment = supportFragmentManager.findFragmentByTag(configTag)
            ?: T::class.java.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.configFrameLayout, configFragment, configTag)
            .commit()
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.RECREATE) {
            recreate()
        }
    }

    private fun showPreferenceSearchResults(query: String, searchView: SearchView) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return
        val fragment = supportFragmentManager.findFragmentById(R.id.configFrameLayout)
            as? PreferenceFragmentCompat ?: return
        val results = findPreferenceSearchResults(fragment.preferenceScreen, normalizedQuery)
        if (results.isEmpty()) {
            toastOnUi(R.string.config_search_empty)
            return
        }
        selector(R.string.search, results) { _, result, _ ->
            searchView.setQuery("", false)
            searchView.clearFocus()
            searchView.isIconified = true
            fragment.scrollToPreference(result.preference)
        }
    }

    private fun findPreferenceSearchResults(
        group: PreferenceGroup,
        query: String,
        categories: List<CharSequence> = emptyList(),
    ): List<PreferenceSearchResult> {
        val results = mutableListOf<PreferenceSearchResult>()
        repeat(group.preferenceCount) { index ->
            val preference = group.getPreference(index)
            if (!preference.isVisible) return@repeat
            if (preference is PreferenceGroup) {
                val childCategories = preference.title
                    ?.takeIf { it.isNotBlank() }
                    ?.let { categories + it }
                    ?: categories
                results += findPreferenceSearchResults(preference, query, childCategories)
                return@repeat
            }
            val matches = configPreferenceMatches(
                query,
                preference.title,
                preference.summary,
                categories,
            )
            if (!matches) {
                return@repeat
            }
            val title = preference.title?.takeIf { it.isNotBlank() }
                ?: preference.summary?.takeIf { it.isNotBlank() }
                ?: return@repeat
            results += PreferenceSearchResult(
                label = (categories + title).joinToString(" > "),
                preference = preference,
            )
        }
        return results
    }

    private data class PreferenceSearchResult(
        val label: String,
        val preference: Preference,
    ) {
        override fun toString(): String = label
    }

}

internal fun configPreferenceMatches(
    query: String,
    title: CharSequence?,
    summary: CharSequence?,
    categories: List<CharSequence>,
): Boolean {
    if (query.isBlank()) return false
    return title?.contains(query, ignoreCase = true) == true ||
            summary?.contains(query, ignoreCase = true) == true ||
            categories.any { it.contains(query, ignoreCase = true) }
}
