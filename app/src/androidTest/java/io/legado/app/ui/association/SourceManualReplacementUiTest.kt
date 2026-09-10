package io.legado.app.ui.association

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssSource
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.Restore
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.EffectiveReplacesDialog
import io.legado.app.ui.book.read.ManualReplaceRulesDialog
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.ui.widget.PopupAction
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.runBlocking
import org.hamcrest.Description
import org.hamcrest.TypeSafeMatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class SourceManualReplacementUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val keys = listOf(PreferKey.manualReplaceRule, PreferKey.manualSourceReplaceRule,
        PreferKey.importReplaceSource, PreferKey.autoBackup, PreferKey.importRememberGroup)
    private val savedPrefs = keys.associateWith { prefs.all[it] }
    private val savedRules = appDb.replaceRuleDao.all
    private val savedIgnore = HashMap(BackupConfig.ignoreConfig)
    private val savedLastBackup = LocalConfig.lastBackup
    private val id = UUID.randomUUID().toString()
    private val files = arrayListOf<File>()
    private val urls = (0..1).map { "https://manual-$id.invalid/$it" }
    private val rules = listOf(
        ReplaceRule(id = 9123601, name = "Mixed source and body", pattern = "Seed", replacement = "Seed+",
            scopeSource = true, scopeContent = true, isRegex = false, order = 1),
        ReplaceRule(id = 9123602, name = "Ordered scoped source", pattern = "+", replacement = "++",
            scope = "Seed0", scopeSource = true, scopeContent = false, isRegex = false, order = 2),
        ReplaceRule(id = 9123603, name = "Excluded source", pattern = "Seed0", replacement = "Wrong",
            excludeScope = urls[0], scopeSource = true, scopeContent = false, isRegex = false, order = 3),
        ReplaceRule(id = 9123604, name = "No matching text", pattern = "Absent", replacement = "Wrong",
            scopeSource = true, scopeContent = false, isRegex = false, order = 4),
        ReplaceRule(id = 9123605, name = "Disabled source", pattern = "Seed", replacement = "Wrong",
            scopeSource = true, scopeContent = false, isEnabled = false, isRegex = false, order = 5),
        ReplaceRule(id = 9123606, name = "Body only", pattern = "Seed", replacement = "Body",
            scopeSource = false, scopeContent = true, isRegex = false, order = 6),
    )

    @Before fun setup() {
        prefs.edit().remove(PreferKey.manualReplaceRule).remove(PreferKey.manualSourceReplaceRule)
            .putBoolean(PreferKey.importReplaceSource, true).putBoolean(PreferKey.autoBackup, false)
            .putBoolean(PreferKey.importRememberGroup, false).commit()
        savedRules.forEach { appDb.replaceRuleDao.insert(it.copy(isEnabled = false)) }
        appDb.replaceRuleDao.insert(*rules.toTypedArray())
    }

    @After fun cleanup() {
        appDb.replaceRuleDao.delete(*rules.toTypedArray())
        appDb.replaceRuleDao.insert(*savedRules.toTypedArray())
        urls.forEach { appDb.bookSourceDao.delete(it); appDb.rssSourceDao.delete(it) }
        files.forEach { it.delete() }
        prefs.edit().apply {
            savedPrefs.forEach { (key, value) ->
                if (value is Boolean) putBoolean(key, value) else remove(key)
            }
        }.commit()
        BackupConfig.ignoreConfig.clear()
        BackupConfig.ignoreConfig.putAll(savedIgnore)
        LocalConfig.lastBackup = savedLastBackup
    }

    @Test fun bookManualAndEffectiveMenusUseRawCandidates() = manualFlow(false)
    @Test fun rssManualAndEffectiveMenusUseRawCandidates() = manualFlow(true)

    private fun manualFlow(rss: Boolean) {
        AppConfig.manualSourceReplaceRule = true
        withImport(rss) { host ->
            host.names("Seed0", "Seed1")
            main {
                host.view<SearchView>(R.id.source_import_search).setQuery("Seed0", false)
                if (rss) host.feed.setSelection(1, false) else host.book.setSelection(1, false)
            }
            host.menu(R.id.menu_manual_replace_rule)
            var manual = host.child<ManualReplaceRulesDialog>()
            assertEquals(rules.take(4).map { it.id }, main { ruleIds(manual) })
            main { manual.requireView().findViewById<View>(R.id.tv_footer_left).performClick() }
            host.scenario.recreate()
            host.findParent()
            manual = host.child()
            main { manual.requireView().findViewById<View>(R.id.tv_ok).performClick() }
            host.ready()
            host.names("Seed++0", "Seed+1") // Hidden, unchecked candidate is also replaced.
            main {
                assertEquals(listOf(true, false), if (rss) host.feed.selectStatus else host.book.selectStatus)
                assertEquals("Seed0", host.view<SearchView>(R.id.source_import_search).query.toString())
                host.view<SearchView>(R.id.source_import_search).setQuery("", false)
            }
            host.menu(R.id.menu_effective_replaces)
            val effective = host.child<EffectiveReplacesDialog>()
            main { assertEquals(rules.take(2).map { it.id }, ruleIds(effective)); effective.dismiss() }
            host.ready()
            var code = host.open(0)
            main {
                code.binding.cbSourceReplacementPreview.isChecked = false
                code.binding.codeView.setText(GSON.toJson(source(rss, 0, "Edited Seed0")))
            }
            host.menu(R.id.menu_manual_replace_rule, code)
            manual = host.child()
            main {
                // Clear inherited global selection, then choose the mixed-scope rule only.
                manual.requireView().findViewById<View>(R.id.tv_footer_left).performClick()
            }
            clickRule(manual, 0)
            main { manual.requireView().findViewById<View>(R.id.tv_ok).performClick() }
            host.ready(code)
            host.names("Edited Seed+0", "Seed+1")
            host.menu(R.id.menu_effective_replaces, code)
            val single = host.child<EffectiveReplacesDialog>()
            main { assertEquals(listOf(rules[0].id), ruleIds(single)); single.dismiss() }
            host.ready(code)
            // Reopening and confirming must not apply the non-idempotent rule twice.
            host.menu(R.id.menu_manual_replace_rule, code)
            manual = host.child()
            main { manual.requireView().findViewById<View>(R.id.tv_ok).performClick() }
            host.ready(code)
            host.names("Edited Seed+0", "Seed+1")
            host.scenario.recreate()
            host.findParent()
            code = host.child()
            host.ready(code)
            main { assertTrue(code.currentOriginalCode().contains("Edited Seed0")) }
            main {
                code.binding.cbSourceReplacementPreview.isChecked = false
                code.binding.codeView.setText("{ invalid draft")
            }
            host.menu(R.id.menu_manual_replace_rule, code)
            main {
                assertEquals("{ invalid draft", code.currentOriginalCode())
                assertTrue(host.parent.childFragmentManager.fragments.none { it is ManualReplaceRulesDialog })
            }
            screenshot("source-manual-invalid-$rss")
            main { code.dismiss() }
            host.ready()
            host.names("Edited Seed+0", "Seed+1")
            main { host.view<View>(R.id.tv_ok).performClick() }
            await("Import not persisted") {
                val stored = if (rss) appDb.rssSourceDao.getByKey(urls[0])?.sourceName
                    else appDb.bookSourceDao.getBookSource(urls[0])?.bookSourceName
                stored == "Edited Seed+0"
            }
            assertNull(if (rss) appDb.rssSourceDao.getByKey(urls[1]) else appDb.bookSourceDao.getBookSource(urls[1]))
        }
    }

    @Test fun independentFlagsPreserveLegacyValueAndBackup() = runBlocking {
        assertFalse(AppConfig.manualReplaceRule)
        assertFalse(AppConfig.manualSourceReplaceRule)
        prefs.edit().putBoolean(PreferKey.manualReplaceRule, true).commit()
        ActivityScenario.launch(ReplaceRuleActivity::class.java).use { scenario ->
            assertTrue(AppConfig.manualReplaceRule)
            assertFalse(AppConfig.manualSourceReplaceRule)
            openActionBarOverflowOrOptionsMenu(context)
            onView(withText(R.string.manual_replace_rule)).inRoot(isPlatformPopup()).perform(click())
            onView(withId(R.id.recycler_view)).inRoot(isPlatformPopup()).check { view, error ->
                if (error != null) throw error
                val items = ((view as RecyclerView).adapter as PopupAction.Adapter).getItems()
                assertEquals(listOf(PreferKey.manualReplaceRule, PreferKey.manualSourceReplaceRule), items.map { it.value })
                assertEquals(listOf(true, false), items.map { it.checked })
                assertTrue(items.all { it.checkable && it.icon == null })
            }
            screenshot("source-manual-two-switches")
            onView(withText(R.string.manual_source_replacement)).inRoot(isPlatformPopup()).perform(click())
            assertTrue(AppConfig.manualSourceReplaceRule)
            assertTrue(AppConfig.manualReplaceRule)
        }
        BackupConfig.contentKeys.forEach { BackupConfig.ignoreConfig[it] = it != BackupConfig.settingContentKey }
        val archive = Backup.backupForLanTransferLocked(context).also { files.add(it) }
        ZipFile(archive).use { zip ->
            val xml = zip.getInputStream(checkNotNull(zip.getEntry("config.xml"))).bufferedReader().use { it.readText() }
            assertTrue(xml.contains("name=\"manualReplaceRule\" value=\"true\""))
            assertTrue(xml.contains("name=\"manualSourceReplaceRule\" value=\"true\""))
        }
        AppConfig.manualReplaceRule = false
        AppConfig.manualSourceReplaceRule = false
        Restore.restoreOrThrow(context, archive.toUri(), lanTransfer = true)
        assertTrue(AppConfig.manualReplaceRule)
        assertTrue(AppConfig.manualSourceReplaceRule)
        for (readerManual in listOf(false, true)) for (sourceManual in listOf(false, true)) {
            AppConfig.manualReplaceRule = readerManual
            AppConfig.manualSourceReplaceRule = sourceManual
            val book = Book(bookUrl = "https://reader-$id.invalid", name = "Reader $id", origin = "Origin $id")
            book.setUseReplaceRule(true)
            val chapter = BookChapter(bookUrl = book.bookUrl, url = "chapter", title = "Chapter")
            val text = ReadBook.processChapterContent(book, chapter, "Seed body").second.toString()
            assertEquals("Reader flag is independent of source flag", !readerManual, text.contains("Body+"))
            assertEquals(readerManual, text.contains("Seed body"))
            for (rss in listOf(false, true)) withImport(rss) { host ->
                if (sourceManual) host.names("Seed0", "Seed1") else host.names("Seed++0", "Seed+1")
                main { host.view<View>(R.id.tv_cancel).performClick() }
            }
        }
        val readerCandidates = appDb.replaceRuleDao.findManualCandidates().map { it.id }
        assertTrue(rules[0].id in readerCandidates)
        assertTrue(rules[5].id in readerCandidates)
        assertTrue(rules.subList(1, 5).none { it.id in readerCandidates })
    }

    private fun source(rss: Boolean, index: Int, name: String = "Seed$index"): Any =
        if (rss) RssSource(sourceUrl = urls[index], sourceName = name, ruleArticles = "article")
        else BookSource(bookSourceUrl = urls[index], bookSourceName = name, searchUrl = "/search")

    private fun withImport(rss: Boolean, action: (Host) -> Unit) {
        val file = File(context.cacheDir, "source-manual-$id-${files.size}.json").also { files.add(it) }
        file.writeText(GSON.toJson((0..1).map { source(rss, it) }))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", file)
        val intent = Intent(context, FileAssociationActivity::class.java).apply {
            this.action = Intent.ACTION_SEND
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ActivityScenario.launch<FileAssociationActivity>(intent).use { scenario ->
            val host = Host(scenario, rss)
            host.findParent()
            host.ready()
            action(host)
        }
    }

    private inner class Host(val scenario: ActivityScenario<FileAssociationActivity>, val rss: Boolean) {
        lateinit var parent: DialogFragment
        val book get() = ViewModelProvider(parent)[ImportBookSourceViewModel::class.java]
        val feed get() = ViewModelProvider(parent)[ImportRssSourceViewModel::class.java]
        fun <T : View> view(id: Int): T = parent.requireView().findViewById(id)
        fun findParent() = await("Missing import preview") {
            scenario.onActivity { activity ->
                activity.supportFragmentManager.fragments.filterIsInstance<DialogFragment>()
                    .find { if (rss) it is ImportRssSourceDialog else it is ImportBookSourceDialog }
                    ?.let { parent = it }
            }
            ::parent.isInitialized && main { parent.view != null }
        }
        fun ready(top: DialogFragment = parent) = await("Preview still updating") {
            main { view<View>(R.id.tv_ok).isEnabled &&
                top.dialog?.window?.decorView?.hasWindowFocus() == true }
        }
        fun names(vararg names: String) = main {
            assertEquals(names.toList(), if (rss) feed.allSources.map { it.sourceName }
                else book.allSources.map { it.bookSourceName })
        }
        inline fun <reified T : DialogFragment> child(): T {
            var result: T? = null
            await("Missing ${T::class.simpleName}") {
                main {
                    result = parent.childFragmentManager.fragments.filterIsInstance<T>().lastOrNull()
                    result?.dialog?.window?.decorView?.hasWindowFocus() == true
                }
            }
            return checkNotNull(result)
        }
        fun menu(id: Int, dialog: DialogFragment = parent) {
            val toolbar = main { dialog.requireView().findViewById<Toolbar>(R.id.tool_bar) }
            main { assertTrue(toolbar.menu.findItem(id).isVisible); toolbar.showOverflowMenu() }
            await("Missing overflow") { main { toolbar.isOverflowMenuShowing } }
            onData(object : TypeSafeMatcher<Any>() {
                override fun describeTo(description: Description) { description.appendText("menu $id") }
                override fun matchesSafely(item: Any) = item is MenuItem && item.itemId == id
            }).inRoot(isPlatformPopup()).perform(click())
        }
        fun open(index: Int): CodeDialog {
            await("Source row missing") { main { view<RecyclerView>(R.id.recycler_view).let {
                !it.hasPendingAdapterUpdates() && it.findViewHolderForAdapterPosition(index) != null
            } } }
            main { view<RecyclerView>(R.id.recycler_view).findViewHolderForAdapterPosition(index)!!
                .itemView.findViewById<View>(R.id.tv_open).performClick() }
            return child()
        }
    }

    private fun ruleIds(dialog: DialogFragment) =
        (dialog.requireView().findViewById<RecyclerView>(R.id.recycler_view).adapter as RecyclerAdapter<*, *>)
            .getItems().map { (it as ReplaceRule).id }

    private fun clickRule(dialog: DialogFragment, index: Int) {
        await("Rule row missing") { main { dialog.requireView().findViewById<RecyclerView>(R.id.recycler_view)
            .findViewHolderForAdapterPosition(index) != null } }
        main { dialog.requireView().findViewById<RecyclerView>(R.id.recycler_view)
            .findViewHolderForAdapterPosition(index)!!.itemView.performClick() }
    }
    private fun <T> main(action: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = action() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; SystemClock.sleep(50) }
        assertTrue(message, condition())
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        checkNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
            try {
                File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        }
    }
}
