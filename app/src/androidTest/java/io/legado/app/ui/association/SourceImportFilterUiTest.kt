package io.legado.app.ui.association

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssSource
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SourceImportFilterUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val prefKeys = listOf(PreferKey.importReplaceSource, PreferKey.importRememberGroup,
        PreferKey.importLastGroup, PreferKey.importLastGroupAdd, PreferKey.autoBackup)
    private val savedPrefs = prefKeys.associateWith { prefs.all[it] }
    private val savedRules = appDb.replaceRuleDao.findEnabledBySourceScope()
    private val id = UUID.randomUUID().toString()
    private val files = arrayListOf<File>()
    private val urls = arrayListOf<String>()
    private val rule = ReplaceRule(name = "Import filter $id", pattern = "Edited-$id",
        replacement = "Derived-$id", isRegex = false, scopeSource = true, scopeContent = false)

    @Before fun setUp() {
        prefs.edit().remove(PreferKey.importRememberGroup).remove(PreferKey.importLastGroup)
            .remove(PreferKey.importLastGroupAdd).putBoolean(PreferKey.importReplaceSource, false)
            .putBoolean(PreferKey.autoBackup, false).commit()
        savedRules.forEach { appDb.replaceRuleDao.insert(it.copy(isEnabled = false)) }
        appDb.replaceRuleDao.insert(rule)
    }

    @After fun tearDown() {
        urls.forEach { appDb.bookSourceDao.delete(it); appDb.rssSourceDao.delete(it) }
        appDb.replaceRuleDao.delete(rule)
        savedRules.forEach { appDb.replaceRuleDao.insert(it) }
        files.forEach { it.delete() }
        prefs.edit().apply {
            savedPrefs.forEach { (key, value) ->
                when (value) {
                    null -> remove(key)
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                }
            }
        }.commit()
    }

    @Test fun bookFilteringKeepsCandidatesThroughEditReplacementAndRecreation() = filtering(false)

    @Test fun rssFilteringKeepsCandidatesThroughEditReplacementAndRecreation() = filtering(true)

    private fun filtering(rss: Boolean) {
        val candidateUrls = (0..2).map { url("$rss/candidate-$it") }
        val editedUrl = url("$rss/edited")
        val candidates = listOf(
            source(rss, candidateUrls[0], "Hidden", "Other", "hidden comment"),
            source(rss, candidateUrls[1], "Twin", "Precise,Shared", "OnlyNeedle"),
            source(rss, candidateUrls[2], "Twin", "PreciseSuffix,Shared", "other comment"),
        )
        val existing = source(rss, candidateUrls[2], "Old stored name", "Existing", "old", 1)
        if (rss) appDb.rssSourceDao.insert(existing as RssSource)
        else appDb.bookSourceDao.insert(existing as BookSource)
        withImport(rss, candidates) { host ->
            host.selection(true, true, true)
            host.rejectStaleRowAfterQuery("OnlyNeedle", 1)
            host.selection(true, true, true)
            host.query("TWIN", 1, 2)
            host.footer(2, 2, 3, all = true)
            host.click(R.id.tv_footer_left)
            host.selection(true, false, false)
            host.footer(0, 2, 1, all = false)
            screenshot("source-filter-count-$rss")
            if (!rss) {
                host.query("Hidden", 0)
                host.rowClick(0, R.id.cb_source_name)
                host.query("TWIN", 1, 2)
                host.menu(R.id.menu_select_new_source)
                host.selection(false, true, false)
                host.menu(R.id.menu_select_update_source)
                host.selection(false, true, true)
                host.query("Hidden", 0)
                host.rowClick(0, R.id.cb_source_name)
                host.query("TWIN", 1, 2)
            } else {
                host.click(R.id.tv_footer_left)
            }
            host.selection(true, true, true)
            host.rowClick(0, R.id.cb_source_name)
            host.selection(true, false, true)
            host.query("CANDIDATE-1", 1)
            host.query("group:Precise", 1)
            host.query("Shared", 1, 2)
            host.query("OnlyNeedle", 1)
            host.recreate()
            host.assertQuery("OnlyNeedle", 1)
            host.selection(true, false, true)
            host.query("does-not-exist")
            main {
                assertEquals(context.getString(R.string.import_no_results),
                    host.view<TextView>(R.id.tv_msg).text.toString())
                assertTrue(host.view<TextView>(R.id.tv_msg).isShown)
                assertFalse(host.view<View>(R.id.tv_footer_left).isEnabled)
            }
            host.footer(0, 0, 2, all = true)
            host.query("", 0, 1, 2)
            host.query("OnlyNeedle", 1)
            val preview = host.open(0, 1)
            val edited = GSON.toJson(source(rss, editedUrl, "Edited-$id", "Edited group", "edited comment"))
            main {
                assertTrue(preview.currentOriginalCode().contains(candidateUrls[1]))
                assertFalse(preview.currentOriginalCode().contains(candidateUrls[0]))
                preview.binding.codeView.setText(edited)
            }
            onView(withId(R.id.menu_save)).inRoot(isDialog()).perform(click())
            host.awaitReady()
            host.assertQuery("OnlyNeedle")
            host.selection(true, false, true)
            host.query("Edited-$id", 1)
            host.menu(R.id.menu_replace_source)
            host.awaitReady()
            host.assertQuery("Edited-$id")
            host.query("Derived-$id", 1)
            val derivedPreview = host.open(0, 1)
            main {
                assertTrue(derivedPreview.currentOriginalCode().contains("Edited-$id"))
                assertTrue(derivedPreview.binding.codeView.text.toString().contains("Derived-$id"))
                derivedPreview.dismiss()
            }
            host.awaitReady()
            host.selection(true, false, true)
            host.menu(R.id.menu_replace_source)
            host.awaitReady()
            host.assertQuery("Derived-$id")
            host.query("", 0, 1, 2)
            // Clear every selection through the actual footer, then import only original index 1.
            host.click(R.id.tv_footer_left)
            host.click(R.id.tv_footer_left)
            host.selection(false, false, false)
            host.query("Edited-$id", 1)
            host.rowClick(0, R.id.cb_source_name)
            host.selection(false, true, false)
            host.setGroup("Imported group", false)
            host.click(R.id.tv_ok)
            await("Edited $rss source was not imported") { storedGroup(rss, editedUrl) == "Imported group" }
            host.awaitFinished()
        }
        assertNull(storedGroup(rss, candidateUrls[0]))
        assertNull(storedGroup(rss, candidateUrls[1]))
        assertEquals("Existing", storedGroup(rss, candidateUrls[2]))
        assertEquals("Old stored name", if (rss) appDb.rssSourceDao.getByKey(candidateUrls[2])?.sourceName
            else appDb.bookSourceDao.getBookSource(candidateUrls[2])?.bookSourceName)
        val savedName = if (rss) appDb.rssSourceDao.getByKey(editedUrl)?.sourceName
            else appDb.bookSourceDao.getBookSource(editedUrl)?.bookSourceName
        assertEquals("Edited-$id", savedName)
    }

    @Test fun specialFiltersMatchManagementGroupAndLoginSemantics() {
        for (rss in listOf(false, true)) {
            val candidates = listOf("未分组", "Ungrouped", "RSS", "rss").mapIndexed { index, group ->
                source(rss, url("$rss/metadata-$index"), group = group).also {
                    when (it) {
                        is BookSource -> when (index) {
                            0 -> { it.mainJs = "function login() {}"; it.loginUi = "[{\"name\":\"account\"}]" }
                            1 -> it.loginUrl = "https://login.invalid"
                        }
                        is RssSource -> if (index == 1) it.loginUrl = "https://login.invalid"
                    }
                }
            }
            withImport(rss, candidates) { host ->
                host.query("group:RSS", 2)
                host.query("group:rss", 3)
                host.query(context.getString(R.string.no_group), 0)
                host.query(context.getString(R.string.need_login), *if (rss) intArrayOf(1) else intArrayOf(0, 1))
                host.click(R.id.tv_cancel)
                host.awaitFinished()
            }
        }
    }

    @Test fun groupMemoryIsOptInSharedBetweenBookAndRssAndDisablingClearsIt() {
        assertFalse("The unset preference must default to OFF", AppConfig.importRememberGroup)
        val first = url("group/default-off")
        withImport(false, listOf(source(false, first))) { host ->
            host.group(null, false)
            host.setGroup("Once", false)
            assertNull(AppConfig.importLastGroup)
            host.click(R.id.tv_ok)
            await("One-time replacement group was not imported") { storedGroup(false, first) == "Once" }
        }
        val second = url("group/remember-add")
        withImport(true, listOf(source(true, second))) { host ->
            host.group(null, false)
            host.menu(R.id.menu_remember_source_group)
            assertTrue(AppConfig.importRememberGroup)
            host.setGroup("Remember add", true)
            assertEquals("Remember add", AppConfig.importLastGroup)
            assertTrue(AppConfig.importLastGroupAdd)
            host.click(R.id.tv_ok)
            await("Remembered add group was not applied") {
                storedGroup(true, second) == "Original,Remember add"
            }
        }
        val third = url("group/remember-replace")
        withImport(false, listOf(source(false, third))) { host ->
            host.group("Remember add", true)
            host.inspectGroupDialog("Remember add", true)
            screenshot("source-remembered-group-book")
            onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
            host.setGroup("Remember replace", false)
            host.click(R.id.tv_ok)
            await("Remembered replacement group was not applied") {
                storedGroup(false, third) == "Remember replace"
            }
        }
        val fourth = url("group/disable")
        withImport(true, listOf(source(true, fourth))) { host ->
            host.group("Remember replace", false)
            host.inspectGroupDialog("Remember replace", false)
            screenshot("source-remembered-group-rss")
            onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
            host.menu(R.id.menu_remember_source_group)
            host.group(null, false)
            assertFalse(AppConfig.importRememberGroup)
            assertNull(AppConfig.importLastGroup)
            assertFalse(AppConfig.importLastGroupAdd)
            host.click(R.id.tv_ok)
            await("Disabling memory must preserve the source's own group") {
                storedGroup(true, fourth) == "Original"
            }
        }
        withImport(false, listOf(source(false, url("group/after-disable")))) { host ->
            host.group(null, false)
            host.menu(R.id.menu_remember_source_group)
            host.group(null, false)
            assertNull(AppConfig.importLastGroup)
            host.click(R.id.tv_cancel)
        }
    }

    private fun source(rss: Boolean, url: String, name: String = "Group fixture",
        group: String = "Original", comment: String = "", time: Long = 100): Any =
        if (rss) RssSource(sourceUrl = url, sourceName = name, sourceGroup = group,
            sourceComment = comment, ruleArticles = "article", lastUpdateTime = time)
        else BookSource(bookSourceUrl = url, bookSourceName = name, bookSourceGroup = group,
            bookSourceComment = comment, searchUrl = "/search", lastUpdateTime = time)

    private fun url(path: String) = "https://filter-$id.invalid/$path".also { urls.add(it) }

    private fun storedGroup(rss: Boolean, url: String): String? = if (rss)
        appDb.rssSourceDao.getByKey(url)?.sourceGroup else appDb.bookSourceDao.getBookSource(url)?.bookSourceGroup

    private fun withImport(rss: Boolean, sources: List<Any>, action: (ImportHost) -> Unit) {
        val file = File(context.cacheDir, "source-filter-$id-${files.size}.json")
        files.add(file)
        file.writeText(GSON.toJson(sources))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", file)
        val intent = Intent(context, FileAssociationActivity::class.java).apply {
            this.action = Intent.ACTION_SEND
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ActivityScenario.launch<FileAssociationActivity>(intent).use { scenario ->
            val host = ImportHost(scenario, rss)
            host.findParent()
            host.awaitReady()
            action(host)
        }
    }

    private inner class ImportHost(val scenario: ActivityScenario<FileAssociationActivity>, val rss: Boolean) {
        lateinit var parent: DialogFragment
        private val book get() = ViewModelProvider(parent)[ImportBookSourceViewModel::class.java]
        private val feed get() = ViewModelProvider(parent)[ImportRssSourceViewModel::class.java]
        fun <T : View> view(id: Int): T = parent.requireView().findViewById(id)
        private fun toolbar() = view<Toolbar>(R.id.tool_bar)
        private fun indices() = (view<RecyclerView>(R.id.recycler_view).adapter as RecyclerAdapter<*, *>).getItems()

        fun findParent() = await("Import dialog missing: rss=$rss") {
            var found: DialogFragment? = null
            scenario.onActivity { activity ->
                found = activity.supportFragmentManager.fragments.filterIsInstance<DialogFragment>()
                    .find { if (rss) it is ImportRssSourceDialog else it is ImportBookSourceDialog }
            }
            found?.also { parent = it } != null
        }

        fun awaitReady() = await("Import dialog not ready: rss=$rss") {
            main { parent.view != null && view<View>(R.id.tv_ok).isEnabled &&
                parent.dialog?.window?.decorView?.hasWindowFocus() == true }
        }

        fun query(query: String, vararg expected: Int) {
            main { view<SearchView>(R.id.source_import_search).setQuery(query, false) }
            assertQuery(query, *expected)
        }

        fun assertQuery(query: String, vararg expected: Int) {
            await("Filter '$query' did not resolve ${expected.toList()}: rss=$rss") {
                main { indices() == expected.toList() }
            }
            main {
                assertEquals(query, view<SearchView>(R.id.source_import_search).query.toString())
                assertEquals(query, if (rss) feed.searchQuery else book.searchQuery)
            }
        }

        fun selection(vararg expected: Boolean) = main {
            assertEquals("Selection must use original indices: rss=$rss", expected.toList(),
                if (rss) feed.selectStatus.toList() else book.selectStatus.toList())
        }

        fun footer(selected: Int, visible: Int, total: Int, all: Boolean) = main {
            assertEquals(context.getString(if (all) R.string.import_unselect_results
                else R.string.import_select_results, selected, visible, total),
                view<TextView>(R.id.tv_footer_left).text.toString())
        }

        fun click(id: Int) { main { assertTrue(view<View>(id).performClick()) } }

        fun rowClick(position: Int, id: Int) {
            await("Filtered row $position is not laid out: rss=$rss") {
                main { view<RecyclerView>(R.id.recycler_view).let {
                    !it.hasPendingAdapterUpdates() && it.findViewHolderForAdapterPosition(position) != null
                } }
            }
            main { view<RecyclerView>(R.id.recycler_view)
                .findViewHolderForAdapterPosition(position)!!.itemView.findViewById<View>(id).performClick() }
        }

        fun rejectStaleRowAfterQuery(query: String, originalIndex: Int) {
            await("Initial row is not laid out") {
                main { view<RecyclerView>(R.id.recycler_view).let {
                    !it.hasPendingAdapterUpdates() && it.findViewHolderForAdapterPosition(0) != null
                } }
            }
            main {
                val holder = view<RecyclerView>(R.id.recycler_view).findViewHolderForAdapterPosition(0)!!
                view<SearchView>(R.id.source_import_search).setQuery(query, false)
                assertEquals(listOf(originalIndex), indices())
                assertEquals(RecyclerView.NO_POSITION, holder.bindingAdapterPosition)
                holder.itemView.findViewById<View>(R.id.cb_source_name).performClick()
                holder.itemView.performClick()
                holder.itemView.findViewById<View>(R.id.tv_open).performClick()
                parent.childFragmentManager.executePendingTransactions()
                assertTrue(parent.childFragmentManager.fragments.none { it is CodeDialog })
            }
            assertQuery(query, originalIndex)
        }

        fun menu(id: Int) {
            val title = main { toolbar().menu.findItem(id).title.toString() }
            main { toolbar().showOverflowMenu() }
            await("Import overflow missing") { main { toolbar().isOverflowMenuShowing } }
            onView(withText(title)).inRoot(isPlatformPopup()).perform(click())
        }

        fun open(position: Int, originalIndex: Int): CodeDialog {
            rowClick(position, R.id.tv_open)
            var code: CodeDialog? = null
            await("Source code preview missing: rss=$rss") {
                main {
                    code = parent.childFragmentManager.fragments.filterIsInstance<CodeDialog>().firstOrNull()
                    code?.dialog?.window?.decorView?.hasWindowFocus() == true
                }
            }
            return checkNotNull(code).also { main { assertEquals(originalIndex.toString(), it.requestId) } }
        }

        fun recreate() { scenario.recreate(); findParent(); awaitReady() }

        fun awaitFinished() = await("Dismissed import host must finish") {
            scenario.state == Lifecycle.State.DESTROYED
        }

        fun group(expected: String?, add: Boolean) = main {
            assertEquals(expected, if (rss) feed.groupName else book.groupName)
            assertEquals(add, if (rss) feed.isAddGroup else book.isAddGroup)
            val title = expected?.let { context.getString(R.string.diy_edit_source_group_title, it) }
                ?.let { if (add) "+$it" else it } ?: context.getString(R.string.diy_source_group)
            assertEquals(title, toolbar().menu.findItem(R.id.menu_new_group).title.toString())
            assertEquals(AppConfig.importRememberGroup, toolbar().menu.findItem(R.id.menu_remember_source_group).isChecked)
            assertNull(toolbar().menu.findItem(R.id.menu_remember_source_group).icon)
            val menuIds = (0 until toolbar().menu.size()).map { toolbar().menu.getItem(it).itemId }
            assertEquals(menuIds.indexOf(R.id.menu_show_comment) + 1,
                menuIds.indexOf(R.id.menu_remember_source_group))
        }

        fun inspectGroupDialog(expected: String?, add: Boolean) {
            onView(withId(R.id.menu_new_group)).inRoot(isDialog()).perform(click())
            onView(withId(R.id.edit_view)).inRoot(isDialog()).check(matches(withText(expected.orEmpty())))
            onView(withId(R.id.sw_add_group)).inRoot(isDialog()).check(matches(if (add) isChecked() else not(isChecked())))
        }

        fun setGroup(value: String, add: Boolean) {
            val current = main { (if (rss) feed.groupName else book.groupName) to
                (if (rss) feed.isAddGroup else book.isAddGroup) }
            inspectGroupDialog(current.first, current.second)
            onView(withId(R.id.edit_view)).inRoot(isDialog()).perform(replaceText(value))
            if (current.second != add) onView(withId(R.id.sw_add_group)).inRoot(isDialog()).perform(click())
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
            group(value, add)
        }
    }

    private fun <T> main(action: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = action() }
        @Suppress("UNCHECKED_CAST") return result as T
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
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
