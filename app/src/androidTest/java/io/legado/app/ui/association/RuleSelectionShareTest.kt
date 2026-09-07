package io.legado.app.ui.association

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.config.ReplacePreviewConfig
import io.legado.app.ui.book.toc.rule.TxtTocRuleActivity
import io.legado.app.ui.book.toc.rule.TxtTocRuleAdapter
import io.legado.app.ui.dict.rule.DictRuleActivity
import io.legado.app.ui.dict.rule.DictRuleAdapter
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.ui.replace.ReplaceRuleAdapter
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RuleSelectionShareTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.applicationContext

    @Test
    fun replacementSelectionSharesAnImportableFileIncludingItsPreviewSample() {
        val id = UUID.randomUUID().toString()
        val rule = ReplaceRule(
            id = 0, name = "Share replacement $id", pattern = "target", replacement = "changed",
            group = "Sample group", scope = "Fixture", scopeTitle = true, isEnabled = false,
        )
        val other = ReplaceRule(id = 0, name = "Unselected replacement $id", pattern = "other")
        val inserted = appDb.replaceRuleDao.insert(rule, other)
        rule.id = inserted[0]
        other.id = inserted[1]
        val sample = "Title\ntarget sample & ? query"
        ReplacePreviewConfig.saveSample(rule.id, sample)
        try {
            val json = shareSelection(ReplaceRuleActivity::class.java, R.menu.replace_rule_sel) { activity ->
                val adapter = activity.findViewById<RecyclerView>(R.id.recycler_view).adapter as ReplaceRuleAdapter
                val index = adapter.getItems().indexOfFirst { it.id == rule.id }
                if (index < 0 || adapter.getItems().none { it.id == other.id }) false else {
                    assertTrue(adapter.dragSelectCallback.onSelectChange(index, true))
                    assertEquals(listOf(rule.id), adapter.selection.map { it.id })
                    true
                }
            }
            val restored = GSON.fromJsonArray<ReplaceRule>(json).getOrThrow().single()
            assertEquals(rule.id, restored.id)
            assertEquals(rule.name, restored.name)
            assertEquals(rule.pattern, restored.pattern)
            assertEquals(rule.replacement, restored.replacement)
            assertEquals(rule.group, restored.group)
            assertEquals(rule.scope, restored.scope)
            assertTrue(restored.scopeTitle)
            assertFalse(restored.isEnabled)
            assertEquals(sample, restored.previewText)
            assertNull(rule.previewText)
            assertEquals(sample, ReplacePreviewConfig.sample(rule.id))
        } finally {
            ReplacePreviewConfig.removeSample(rule.id)
            appDb.replaceRuleDao.delete(rule, other)
        }
    }

    @Test
    fun txtTocSelectionSharesAllRuleFieldsWithoutUnselectedRules() {
        val id = System.currentTimeMillis()
        val rule = TxtTocRule(
            id = id, name = "Share toc $id", rule = "^Chapter (.+)$", replacement = "$1",
            example = "Chapter One", serialNumber = 42, enable = false,
        )
        val other = TxtTocRule(id = id + 1, name = "Unselected toc $id", rule = "Other")
        appDb.txtTocRuleDao.insert(rule, other)
        try {
            val json = shareSelection(TxtTocRuleActivity::class.java, R.menu.txt_toc_rule_sel) { activity ->
                val adapter = activity.findViewById<RecyclerView>(R.id.recycler_view).adapter as TxtTocRuleAdapter
                val index = adapter.getItems().indexOfFirst { it.id == rule.id }
                if (index < 0 || adapter.getItems().none { it.id == other.id }) false else {
                    assertTrue(adapter.dragSelectCallback.onSelectChange(index, true))
                    assertEquals(listOf(rule.id), adapter.selection.map { it.id })
                    true
                }
            }
            val restored = GSON.fromJsonArray<TxtTocRule>(json).getOrThrow().single()
            assertEquals(GSON.toJson(rule), GSON.toJson(restored))
        } finally {
            appDb.txtTocRuleDao.delete(rule, other)
        }
    }

    @Test
    fun dictionarySelectionSharesTheCompleteUrlAndDisplayRule() {
        val id = UUID.randomUUID().toString()
        val rule = DictRule(
            name = "Share dictionary $id",
            urlRule = "https://example.invalid/dictionary?q={{key}}&lang=zh#result",
            showRule = "@js:result + ' & ? # '", enabled = false, sortNumber = 42,
        )
        val other = DictRule(name = "Unselected dictionary $id", urlRule = "https://example.invalid/other")
        appDb.dictRuleDao.insert(rule, other)
        try {
            val json = shareSelection(DictRuleActivity::class.java, R.menu.dict_rule_sel) { activity ->
                val adapter = activity.findViewById<RecyclerView>(R.id.recycler_view).adapter as DictRuleAdapter
                val index = adapter.getItems().indexOfFirst { it.name == rule.name }
                if (index < 0 || adapter.getItems().none { it.name == other.name }) false else {
                    assertTrue(adapter.dragSelectCallback.onSelectChange(index, true))
                    assertEquals(listOf(rule.name), adapter.selection.map { it.name })
                    true
                }
            }
            val restored = GSON.fromJsonArray<DictRule>(json).getOrThrow().single()
            assertEquals(GSON.toJson(rule), GSON.toJson(restored))
        } finally {
            appDb.dictRuleDao.delete(rule, other)
        }
    }

    @Suppress("DEPRECATION")
    private fun <T : Activity> shareSelection(
        activityClass: Class<T>, menuResource: Int, select: (T) -> Boolean,
    ): String {
        val chooser = AtomicReference<Intent?>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                chooser.set(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        var sharedUri: Uri? = null
        try {
            ActivityScenario.launch(activityClass).use { scenario ->
                fun clickShare(activity: T) {
                    val popup = PopupMenu(activity, activity.findViewById(android.R.id.content))
                    activity.menuInflater.inflate(menuResource, popup.menu)
                    val item = checkNotNull(popup.menu.findItem(R.id.menu_share_source))
                    (activity as PopupMenu.OnMenuItemClickListener).onMenuItemClick(item)
                }
                scenario.onActivity(::clickShare)
                instrumentation.waitForIdleSync()
                assertNull("Empty selection must not launch a share", chooser.get())
                val deadline = SystemClock.uptimeMillis() + 15000
                var selected = false
                while (!selected && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { selected = select(it) }
                    if (!selected) SystemClock.sleep(50)
                }
                assertTrue("Selected fixture was not loaded", selected)
                scenario.onActivity(::clickShare)
                while (chooser.get() == null && SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(50)
                }
                val intent = checkNotNull(chooser.get()) { "No file share intent" }
                    .getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                assertEquals(Intent.ACTION_SEND, intent.action)
                assertEquals("text/*", intent.type)
                assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                val uri = checkNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                sharedUri = uri
                assertEquals("content", uri.scheme)
                return context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            sharedUri?.lastPathSegment?.let { File(context.cacheDir, it).delete() }
        }
    }
}
