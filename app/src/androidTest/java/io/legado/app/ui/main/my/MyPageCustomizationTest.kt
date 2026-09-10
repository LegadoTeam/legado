package io.legado.app.ui.main.my

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.Restore
import io.legado.app.help.storage.writePreferenceSnapshot
import io.legado.app.service.McpService
import io.legado.app.service.WebService
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.config.ConfigTag
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.main.my.MyFragment.MyPreferenceFragment
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.anything
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MyPageCustomizationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val savedPrefs = prefs.all
    private val savedLocal = LocalConfig.all
    private var scenario: ActivityScenario<MainActivity>? = null
    private var moreActivity: ConfigActivity? = null
    private val defaults = setOf("check_update", "check_beta_update")

    @Before fun setUp() {
        prefs.edit().remove(PreferKey.myMoreItems)
            .putBoolean(PreferKey.autoRefresh, false).putBoolean(PreferKey.autoCheckNewBackup, false)
            .putBoolean("autoUpdateVariant", false).putString(PreferKey.defaultHomePage, "settings").commit()
        LocalConfig.edit().putBoolean("privacyPolicyOk", true)
            .putLong("appVersionCode", appInfo.versionCode).putString("password", "").commit()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitMain { it.findPreference<Preference>("autoTaskManage")?.isVisible == true }
    }

    @After fun cleanUp() {
        instrumentation.runOnMainSync { moreActivity?.finish() }
        scenario?.close()
        prefs.edit().clear().apply { savedPrefs.forEach { (key, value) -> putValue(key, value) } }.commit()
        LocalConfig.edit().clear().apply { savedLocal.forEach { (key, value) -> putValue(key, value) } }.commit()
    }

    @Test fun actualPickerMovesOnlySelectedItemsAndMoreKeepsExistingActions() {
        val webRunning = WebService.isRun
        val mcpRunning = McpService.isRun
        val scheduled = prefs.getBoolean(PreferKey.autoTaskService, false)
        openPicker()
        choose("autoTaskManage")
        onView(withText(android.R.string.cancel)).inRoot(isDialog()).perform(click())
        awaitMain { it.findPreference<Preference>("autoTaskManage")!!.isVisible }
        assertEquals(defaults, prefs.getStringSet(PreferKey.myMoreItems, emptySet()))

        openPicker()
        for (key in listOf("autoTaskManage", PreferKey.autoTaskService, PreferKey.webService,
            PreferKey.mcpService, "txtTocRuleManage", "replaceManage", "dictRuleManage", "check_update")) choose(key)
        screenshot("my-customization-picker")
        scenario!!.recreate()
        // The real native dialog must retain its unsaved checked items across recreation.
        onView(withText(android.R.string.ok)).inRoot(isDialog()).perform(click())
        val selected = setOf("autoTaskManage", PreferKey.autoTaskService, PreferKey.webService,
            PreferKey.mcpService, "txtTocRuleManage", "replaceManage", "dictRuleManage", "check_beta_update")
        assertEquals(selected, prefs.getStringSet(PreferKey.myMoreItems, emptySet()))
        awaitMain { !it.findPreference<Preference>("autoTaskManage")!!.isVisible &&
            it.findPreference<Preference>("check_update")!!.isVisible }
        assertEquals(webRunning, WebService.isRun)
        assertEquals(mcpRunning, McpService.isRun)
        assertEquals(scheduled, prefs.getBoolean(PreferKey.autoTaskService, false))
        screenshot("my-customization-main")
        val monitor = instrumentation.addMonitor(ConfigActivity::class.java.name, null, false)
        try {
            scenario!!.onActivity { activity ->
                val fragment = main(activity)
                fragment.onPreferenceTreeClick(fragment.findPreference<Preference>("myMore")!!)
            }
            moreActivity = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? ConfigActivity
            assertNotNull(moreActivity)
            awaitMore { fragment ->
                selected.all { fragment.findPreference<Preference>(it)?.isVisible == true } &&
                    fragment.findPreference<Preference>("check_update")?.isVisible == false &&
                    fragment.findPreference<Preference>("bookSourceManage")?.isVisible == false
            }
            screenshot("my-customization-more")
            instrumentation.runOnMainSync {
                val fragment = moreActivity!!.supportFragmentManager.findFragmentByTag(ConfigTag.MY_MORE) as MyPreferenceFragment
                assertEquals(context.getString(R.string.reader_menu_more), moreActivity!!.title)
                assertEquals(prefs.getBoolean(PreferKey.webService, false),
                    fragment.findPreference<io.legado.app.lib.prefs.SwitchPreference>(PreferKey.webService)!!.isChecked)
                moreActivity!!.finish()
            }
            moreActivity = null
        } finally { instrumentation.removeMonitor(monitor) }
        awaitMain { it.findPreference<Preference>("check_update")!!.isVisible }
        scenario!!.recreate()
        awaitMain { !it.findPreference<Preference>("autoTaskManage")!!.isVisible }
    }

    @Test fun realConfigRestoreAndLegacyBackupRestoreTheVisiblePage() {
        val directory = File(context.cacheDir, "my-page-backup-${UUID.randomUUID()}").apply { mkdirs() }
        val selected = setOf("autoTaskManage", PreferKey.mcpService)
        try {
            writePreferenceSnapshot(context, directory.absolutePath, "config") {
                putStringSet(PreferKey.myMoreItems, selected)
            }
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.absolutePath) }
            awaitMain { !it.findPreference<Preference>("autoTaskManage")!!.isVisible &&
                it.findPreference<Preference>("check_beta_update")!!.isVisible }
            assertEquals(selected, prefs.getStringSet(PreferKey.myMoreItems, emptySet()))
            writePreferenceSnapshot(context, directory.absolutePath, "config") { putBoolean("enableReadRecord", true) }
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.absolutePath) }
            awaitMain { it.findPreference<Preference>("autoTaskManage")!!.isVisible &&
                !it.findPreference<Preference>("check_beta_update")!!.isVisible }
            scenario!!.recreate()
            awaitMain { it.findPreference<Preference>("autoTaskManage")!!.isVisible }
        } finally { directory.deleteRecursively() }
    }

    private fun main(activity: MainActivity): MyPreferenceFragment = activity.supportFragmentManager.fragments
        .filterIsInstance<MyFragment>().single().childFragmentManager.findFragmentByTag("prefFragment") as MyPreferenceFragment

    private fun openPicker() {
        scenario!!.onActivity { main(it).showCustomization() }
        instrumentation.waitForIdleSync()
    }

    private fun choose(key: String) {
        var index = -1
        scenario!!.onActivity {
            index = main(it).findPreference<MultiSelectListPreference>(PreferKey.myMoreItems)!!.entryValues.indexOf(key)
        }
        assertTrue(index >= 0)
        onData(anything()).inRoot(isDialog()).atPosition(index).perform(click())
    }

    private fun awaitMain(condition: (MyPreferenceFragment) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        do {
            var ready = false
            scenario!!.onActivity { activity -> ready = runCatching { condition(main(activity)) }.getOrDefault(false) }
            if (ready) return
            SystemClock.sleep(30)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("My page did not reach the expected state")
    }

    private fun awaitMore(condition: (MyPreferenceFragment) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        do {
            var ready = false
            instrumentation.runOnMainSync {
                val fragment = moreActivity?.supportFragmentManager?.findFragmentByTag(ConfigTag.MY_MORE) as? MyPreferenceFragment
                ready = fragment?.let(condition) == true
            }
            if (ready) return
            SystemClock.sleep(30)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("More page did not reach the expected state")
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream()
                .use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
    }

    private fun SharedPreferences.Editor.putValue(key: String, value: Any?) {
        when (value) {
            is Boolean -> putBoolean(key, value)
            is String -> putString(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>) }
        }
    }
}
