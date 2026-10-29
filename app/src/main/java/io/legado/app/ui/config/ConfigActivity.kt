package io.legado.app.ui.config

import io.legado.app.utils.observeSharedPreferences
import io.legado.app.lib.theme.primaryTextColor
import android.os.Bundle
import android.view.Menu
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import io.legado.app.ui.main.my.MyFragment.MyPreferenceFragment
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
        setupAdaptiveSettings()
        when (val configTag = intent.getStringExtra("configTag")) {
            ConfigTag.MY_MORE -> replaceFragment<MyPreferenceFragment>(configTag)
            ConfigTag.OTHER_CONFIG -> replaceFragment<OtherConfigFragment>(configTag)
            ConfigTag.THEME_CONFIG -> replaceFragment<ThemeConfigFragment>(configTag)
            ConfigTag.BACKUP_CONFIG -> replaceFragment<BackupConfigFragment>(configTag)
            ConfigTag.COVER_CONFIG -> replaceFragment<CoverConfigFragment>(configTag)
            ConfigTag.COVER_FONT_CONFIG -> replaceFragment<CoverFontConfigFragment>(configTag)
            ConfigTag.WELCOME_CONFIG -> replaceFragment<WelcomeConfigFragment>(configTag)
            else -> finish()
        }
    }

    private lateinit var adaptiveRow: io.legado.app.help.adaptive.AdaptiveSettingsRow
    private lateinit var adaptiveNavigation: android.widget.ScrollView
    private lateinit var adaptiveDivider: android.view.View

    private fun setupAdaptiveSettings() {
        val frame = binding.configFrameLayout
        val root = frame.parent as android.widget.LinearLayout
        root.removeView(frame)
        val density = resources.displayMetrics.density
        val rows = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        adaptiveNavigation = android.widget.ScrollView(this).apply {
            isFillViewport = true
            visibility = android.view.View.GONE
            addView(rows)
        }
        adaptiveDivider = android.view.View(this).apply {
            setBackgroundColor(0x33888888)
            visibility = android.view.View.GONE
        }
        adaptiveRow = io.legado.app.help.adaptive.AdaptiveSettingsRow(this).apply {
            addView(adaptiveNavigation, android.widget.LinearLayout.LayoutParams(0, -1, 1f))
            addView(adaptiveDivider, android.widget.LinearLayout.LayoutParams((density).toInt().coerceAtLeast(1), -1))
            addView(frame, android.widget.LinearLayout.LayoutParams(0, -1, 1f))
        }
        root.addView(adaptiveRow, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        val pages: List<Pair<String, () -> Unit>> = listOf(
            "主题设置" to { replaceFragment<ThemeConfigFragment>(ConfigTag.THEME_CONFIG) },
            "备份与恢复" to { replaceFragment<BackupConfigFragment>(ConfigTag.BACKUP_CONFIG) },
            "封面设置" to { replaceFragment<CoverConfigFragment>(ConfigTag.COVER_CONFIG) },
            "封面字体" to { replaceFragment<CoverFontConfigFragment>(ConfigTag.COVER_FONT_CONFIG) },
            "欢迎页设置" to { replaceFragment<WelcomeConfigFragment>(ConfigTag.WELCOME_CONFIG) },
            "其他设置" to { replaceFragment<OtherConfigFragment>(ConfigTag.OTHER_CONFIG) })
        pages.forEach { (label, action) ->
            rows.addView(android.widget.TextView(this).apply {
                text = label
                textSize = 16f
                gravity = android.view.Gravity.CENTER_VERTICAL
                minHeight = (64 * density).toInt()
                setPadding((20 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
                setTextColor(this@ConfigActivity.primaryTextColor)
                val background = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, background, true)
                setBackgroundResource(background.resourceId)
                setOnClickListener { action() }
            }, android.widget.LinearLayout.LayoutParams(-1, -2))
        }
        adaptiveRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateAdaptiveSettings() }
        adaptiveRow.post { updateAdaptiveSettings() }
        observeSharedPreferences { _, key ->
            if (key == io.legado.app.constant.PreferKey.tabletFoldOptimization)
                adaptiveRow.post { updateAdaptiveSettings() }
        }
    }

    private fun updateAdaptiveSettings() {
        val usePanes = io.legado.app.help.adaptive.AdaptivePanePolicy.useTwoPanes(
            io.legado.app.help.adaptive.AdaptiveUiController.enabled(this),
            (adaptiveRow.width / resources.displayMetrics.density).toInt(), resources.configuration.screenHeightDp
        ) && !io.legado.app.help.adaptive.AdaptiveUiController.embedded(this)
        val visibility = if (usePanes) android.view.View.VISIBLE else android.view.View.GONE
        if (adaptiveNavigation.visibility != visibility) {
            adaptiveNavigation.visibility = visibility
            adaptiveDivider.visibility = visibility
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
