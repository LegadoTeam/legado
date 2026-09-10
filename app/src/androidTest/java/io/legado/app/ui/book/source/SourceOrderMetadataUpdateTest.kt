package io.legado.app.ui.book.source

import android.os.SystemClock
import android.widget.CompoundButton
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssSource
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.ui.replace.ReplaceRuleAdapter
import io.legado.app.ui.replace.ReplaceRuleViewModel
import io.legado.app.ui.rss.source.manage.RssSourceActivity
import io.legado.app.ui.rss.source.manage.RssSourceAdapter
import io.legado.app.ui.rss.source.manage.RssSourceViewModel
import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** A displayed row can predate a committed move or an edit from another screen. */
@RunWith(AndroidJUnit4::class)
class SourceOrderMetadataUpdateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun staleRssSwitchAndSelectionPreserveCommittedOrderAndMetadata() {
        val group = "Enable ${UUID.randomUUID()}"
        val before = appDb.rssSourceDao.all
        val stale = (0..2).map { index ->
            RssSource(sourceUrl = "https://enable.invalid/$group/$index",
                sourceName = "Source $index", sourceGroup = group, enabled = false,
                customOrder = index * 100, sourceComment = "Before $index")
        }
        val keys = stale.map { it.sourceUrl }.toSet()
        try {
            appDb.rssSourceDao.insert(*stale.toTypedArray())
            ActivityScenario.launch(RssSourceActivity::class.java).use { scenario ->
                val expectedOrder = appDb.rssSourceDao.all.map { it.sourceUrl }.toMutableList().apply {
                    remove(stale[0].sourceUrl)
                    add(indexOf(stale[2].sourceUrl) + 1, stale[0].sourceUrl)
                }
                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[RssSourceViewModel::class.java]
                        .move(stale[0].sourceUrl, stale[2].sourceUrl, true)
                    activity.findViewById<SearchView>(R.id.search_view).setQuery("group:$group", false)
                }
                waitUntil("RSS move committed") {
                    appDb.rssSourceDao.all.map { it.sourceUrl } == expectedOrder
                }
                appDb.rssSourceDao.update(*appDb.rssSourceDao.getRssSources(*keys.toTypedArray()).map {
                    it.copy(sourceComment = "Updated comment", ruleContent = "body@text")
                }.toTypedArray())
                val committed = appDb.rssSourceDao.all
                fun assertState(enabledKeys: Set<String>) {
                    val expected = committed.associate { source ->
                        source.sourceUrl to GSON.toJson(if (source.sourceUrl in keys)
                            source.copy(enabled = source.sourceUrl in enabledKeys) else source)
                    }
                    assertEquals(expected, appDb.rssSourceDao.all.associate {
                        it.sourceUrl to GSON.toJson(it)
                    })
                }
                waitUntil("RSS switch row") {
                    var ready = false
                    scenario.onActivity { activity ->
                        val recycler = activity.findViewById<RecyclerView>(R.id.recycler_view)
                        val adapter = recycler.adapter as RssSourceAdapter
                        val position = adapter.getItems().indexOfFirst { it.sourceUrl == stale[0].sourceUrl }
                        ready = adapter.getItems().map { it.sourceUrl } == listOf(stale[1].sourceUrl, stale[2].sourceUrl, stale[0].sourceUrl)
                            && recycler.findViewHolderForAdapterPosition(position) != null
                    }
                    ready
                }
                scenario.onActivity { activity ->
                    val recycler = activity.findViewById<RecyclerView>(R.id.recycler_view)
                    val adapter = recycler.adapter as RssSourceAdapter
                    val position = adapter.getItems().indexOfFirst { it.sourceUrl == stale[0].sourceUrl }
                    val row = checkNotNull(recycler.findViewHolderForAdapterPosition(position)).itemView
                    // Force the real switch listener to receive the pre-move snapshot in this UI turn.
                    adapter.setItem(position, stale[0])
                    val switch = row.findViewById<CompoundButton>(R.id.swt_enabled)
                    assertFalse(switch.isChecked)
                    switch.performClick()
                    assertTrue(switch.isChecked)
                }
                waitUntil("RSS switch enabled") { appDb.rssSourceDao.getByKey(stale[0].sourceUrl)?.enabled == true }
                assertState(setOf(stale[0].sourceUrl))
                scenario.onActivity { ViewModelProvider(it)[RssSourceViewModel::class.java].enableSelection(stale) }
                waitUntil("RSS selection enabled") { keys.all { appDb.rssSourceDao.getByKey(it)?.enabled == true } }
                assertState(keys)
                scenario.onActivity { ViewModelProvider(it)[RssSourceViewModel::class.java].disableSelection(stale) }
                waitUntil("RSS selection disabled") { keys.all { appDb.rssSourceDao.getByKey(it)?.enabled == false } }
                assertState(emptySet())
            }
        } finally {
            appDb.rssSourceDao.delete(*stale.toTypedArray())
            val orders = before.associate { it.sourceUrl to it.customOrder }
            appDb.rssSourceDao.update(*appDb.rssSourceDao.all.map {
                it.copy(customOrder = orders[it.sourceUrl] ?: it.customOrder)
            }.toTypedArray())
        }
    }

    @Test
    fun staleReplaceSwitchAndSelectionPreserveCommittedOrderAndMetadata() {
        val group = "Enable ${UUID.randomUUID()}"
        val before = appDb.replaceRuleDao.all
        val firstId = System.currentTimeMillis() * 1000
        val stale = (0..2).map { index ->
            ReplaceRule(id = firstId + index, name = "Rule $index", group = group,
                pattern = "Before $index", replacement = "Before", order = index * 100,
                isEnabled = false)
        }
        val keys = stale.map { it.id }.toSet()
        try {
            appDb.replaceRuleDao.insert(*stale.toTypedArray())
            ActivityScenario.launch(ReplaceRuleActivity::class.java).use { scenario ->
                val expectedOrder = appDb.replaceRuleDao.all.map { it.id }.toMutableList().apply {
                    remove(stale[0].id)
                    add(indexOf(stale[2].id) + 1, stale[0].id)
                }
                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[ReplaceRuleViewModel::class.java]
                        .move(stale[0].id, stale[2].id, true)
                    activity.findViewById<SearchView>(R.id.search_view).setQuery("group:$group", false)
                }
                waitUntil("replacement move committed") {
                    appDb.replaceRuleDao.all.map { it.id } == expectedOrder
                }
                appDb.replaceRuleDao.update(*appDb.replaceRuleDao.findByIds(*keys.toLongArray()).map {
                    it.copy(pattern = "Updated pattern", replacement = "Updated replacement", scope = "Updated scope")
                }.toTypedArray())
                val committed = appDb.replaceRuleDao.all
                fun assertState(enabledKeys: Set<Long>) {
                    val expected = committed.associate { rule ->
                        rule.id to GSON.toJson(if (rule.id in keys)
                            rule.copy(isEnabled = rule.id in enabledKeys) else rule)
                    }
                    assertEquals(expected, appDb.replaceRuleDao.all.associate {
                        it.id to GSON.toJson(it)
                    })
                }
                waitUntil("replacement switch row") {
                    var ready = false
                    scenario.onActivity { activity ->
                        val recycler = activity.findViewById<RecyclerView>(R.id.recycler_view)
                        val adapter = recycler.adapter as ReplaceRuleAdapter
                        val position = adapter.getItems().indexOfFirst { it.id == stale[0].id }
                        ready = adapter.getItems().map { it.id } == listOf(stale[1].id, stale[2].id, stale[0].id)
                            && recycler.findViewHolderForAdapterPosition(position) != null
                    }
                    ready
                }
                scenario.onActivity { activity ->
                    val recycler = activity.findViewById<RecyclerView>(R.id.recycler_view)
                    val adapter = recycler.adapter as ReplaceRuleAdapter
                    val position = adapter.getItems().indexOfFirst { it.id == stale[0].id }
                    val row = checkNotNull(recycler.findViewHolderForAdapterPosition(position)).itemView
                    adapter.setItem(position, stale[0])
                    val switch = row.findViewById<CompoundButton>(R.id.swt_enabled)
                    assertFalse(switch.isChecked)
                    switch.performClick()
                    assertTrue(switch.isChecked)
                }
                waitUntil("replacement switch enabled") { appDb.replaceRuleDao.findById(stale[0].id)?.isEnabled == true }
                assertState(setOf(stale[0].id))
                scenario.onActivity { ViewModelProvider(it)[ReplaceRuleViewModel::class.java].enableSelection(stale) }
                waitUntil("replacement selection enabled") { keys.all { appDb.replaceRuleDao.findById(it)?.isEnabled == true } }
                assertState(keys)
                scenario.onActivity { ViewModelProvider(it)[ReplaceRuleViewModel::class.java].disableSelection(stale) }
                waitUntil("replacement selection disabled") { keys.all { appDb.replaceRuleDao.findById(it)?.isEnabled == false } }
                assertState(emptySet())
            }
        } finally {
            appDb.replaceRuleDao.delete(*stale.toTypedArray())
            val orders = before.associate { it.id to it.order }
            appDb.replaceRuleDao.update(*appDb.replaceRuleDao.all.map {
                it.copy(order = orders[it.id] ?: it.order)
            }.toTypedArray())
        }
    }

    private fun waitUntil(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(50)
        }
        error("Timed out waiting for $description")
    }
}
