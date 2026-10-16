package io.legado.app.ui.main.explore

import android.content.SharedPreferences
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.appcompat.widget.SearchView
import androidx.core.view.children
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Keeps categories and following source headers onscreen throughout the expansion. */
@RunWith(AndroidJUnit4::class)
class ExploreExpansionUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = context.defaultSharedPreferences
    private val savedPreferences = HashMap(preferences.all)
    private val savedLocal = HashMap(LocalConfig.all)
    private val group = "Expansion ${UUID.randomUUID()}"
    private val sources = (0..7).map { index ->
        BookSource(
            bookSourceUrl = "https://explore-expansion.invalid/$group/$index",
            bookSourceName = "Expansion source $index",
            bookSourceGroup = group,
            customOrder = index,
            exploreUrl = (0..8).joinToString(prefix = "[", postfix = "]") { category ->
                """{"title":"Category $index.$category","style":{"layout_flexBasisPercent":0.3}}"""
            },
        )
    }
    private var scenario: ActivityScenario<MainActivity>? = null
    private var savedAnimatorScale: String? = null

    @Before fun setUp() {
        // CI disables animations globally; an end-state-only run would miss this regression.
        savedAnimatorScale = shell("settings get global animator_duration_scale").trim()
        shell("settings put global animator_duration_scale 1")
        preferences.edit().putBoolean(PreferKey.showDiscovery, true)
            .putBoolean(PreferKey.autoRefresh, false)
            .putBoolean(PreferKey.autoCheckNewBackup, false)
            .putBoolean("autoUpdateVariant", false)
            .putString(PreferKey.defaultHomePage, "explore").commit()
        LocalConfig.edit().putBoolean("privacyPolicyOk", true)
            .putLong("appVersionCode", appInfo.versionCode).putString("password", "").commit()
        appDb.bookSourceDao.insert(*sources.toTypedArray())
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await("discovery fragment") { activity ->
            activity.supportFragmentManager.fragments.any { it is ExploreFragment && it.isResumed }
        }
        scenario!!.onActivity { activity ->
            activity.supportFragmentManager.fragments.filterIsInstance<ExploreFragment>().single()
                .requireView().findViewById<SearchView>(R.id.search_view).apply {
                    setQuery("group:$group", false)
                    clearFocus()
                }
        }
        await("fixture sources") { it.list.adapter?.itemCount == sources.size }
        instrumentation.waitForIdleSync()
    }

    @After fun cleanUp() {
        try {
            scenario?.close()
            sources.forEach { source ->
                runBlocking { source.clearExploreKindsCache() }
                appDb.bookSourceDao.delete(source.bookSourceUrl)
            }
            preferences.edit().clear().apply {
                savedPreferences.forEach { (key, value) -> putValue(key, value) }
            }.commit()
            LocalConfig.edit().clear().apply {
                savedLocal.forEach { (key, value) -> putValue(key, value) }
            }.commit()
        } finally {
            savedAnimatorScale?.let { value ->
                shell(if (value == "null") "settings delete global animator_duration_scale"
                    else "settings put global animator_duration_scale $value")
            }
        }
    }

    @Test fun coldCachedAndSwitchedExpansionsNeverOverlapOtherSources() {
        val transitions = listOf(1 to 1, 1 to null, 1 to 1, 2 to 2, 2 to null)
        transitions.forEachIndexed { index, (clickedPosition, expandedPosition) ->
            val overlaps = observeTransition("explore-expansion-$index", expandedPosition) {
                clickSource(clickedPosition)
            }
            assertTrue("Visible content overlapped during transition $index:\n${overlaps.joinToString("\n")}",
                overlaps.isEmpty())
        }
    }

    @Test fun frameProbeDetectsTheOriginalMoveAnimation() {
        // Warm the adapter's category cache, then restore the original animation as a control.
        clickSource(1)
        await("warm categories") { it.hasExpandedSource(1) }
        clickSource(1)
        await("collapsed source") { it.hasExpandedSource(null) && !it.list.isAnimating }
        scenario!!.onActivity { it.list.itemAnimator = DefaultItemAnimator() }
        val overlaps = observeTransition("explore-expansion-original-animator", 1) { clickSource(1) }
        assertTrue("The frame probe must detect the original overlap with animations enabled", overlaps.isNotEmpty())
    }

    private fun clickSource(position: Int) {
        // Invoke the real header listener without waiting for animations to become idle.
        scenario!!.onActivity { activity ->
            val row = checkNotNull(activity.list.findViewHolderForAdapterPosition(position)).itemView
            val header = row.findViewById<View>(R.id.ll_title)
            assertTrue("Source header must be visible", header.getGlobalVisibleRect(Rect()))
            assertTrue("Source header handled its click", header.performClick())
        }
    }

    private fun observeTransition(name: String, expandedPosition: Int?, action: () -> Unit): List<String> {
        val frames = ArrayList<String>()
        val overlaps = ArrayList<String>()
        val rendered = CountDownLatch(1)
        var remainingFrames = -1
        var coVisibleFrames = 0
        lateinit var list: RecyclerView
        lateinit var observer: ViewTreeObserver.OnPreDrawListener
        scenario!!.onActivity { activity ->
            list = activity.list
            observer = ViewTreeObserver.OnPreDrawListener {
                val regions = list.visibleRegions()
                frames.add(regions.joinToString { "${it.position}:${it.label}=${it.bounds}" })
                for (i in regions.indices) for (j in i + 1 until regions.size) {
                    val a = regions[i]
                    val b = regions[j]
                    if (a.position != b.position && Rect.intersects(a.bounds, b.bounds)) {
                        overlaps.add("frame ${frames.size}: $a overlaps $b")
                    }
                }
                if (expandedPosition != null &&
                    regions.any { it.position == expandedPosition && it.label == "category 8" } &&
                    regions.any { it.position == expandedPosition + 1 && it.label == "header" }) {
                    coVisibleFrames++
                }
                if (remainingFrames >= 0) {
                    if (remainingFrames > 0) remainingFrames--
                    if (remainingFrames == 0 && !list.isAnimating) rendered.countDown()
                    else list.postInvalidateOnAnimation()
                }
                true
            }
            list.viewTreeObserver.addOnPreDrawListener(observer)
        }
        try {
            action()
            await("expanded source $expandedPosition") { it.hasExpandedSource(expandedPosition) }
            scenario!!.onActivity { remainingFrames = 18; list.postInvalidateOnAnimation() }
            assertTrue("Transition frames rendered", rendered.await(5, TimeUnit.SECONDS))
            if (expandedPosition != null) {
                assertTrue("Last category and following source must be visible together in multiple frames",
                    coVisibleFrames >= 2)
            }
            return overlaps
        } finally {
            scenario!!.onActivity { list.viewTreeObserver.removeOnPreDrawListener(observer) }
            val directory = File(context.getExternalFilesDir(null), "ui-regression").apply { mkdirs() }
            File(directory, "$name-frames.txt").writeText(frames.joinToString("\n"))
            File(directory, "$name-overlaps.txt").writeText(overlaps.joinToString("\n"))
        }
    }

    private data class Region(val position: Int, val label: String, val bounds: Rect)

    private fun RecyclerView.visibleRegions(): List<Region> = buildList {
        children.forEach { row ->
            val position = getChildAdapterPosition(row)
            if (position == RecyclerView.NO_POSITION || !row.isShown || row.alpha <= 0f) return@forEach
            fun addVisible(view: View, label: String) {
                val bounds = Rect()
                if (view.isShown && view.alpha > 0f && view.getGlobalVisibleRect(bounds)) {
                    add(Region(position, label, bounds))
                }
            }
            addVisible(row.findViewById(R.id.ll_title), "header")
            row.findViewById<ViewGroup>(R.id.flexbox).children.forEachIndexed { index, category ->
                addVisible(category, "category $index")
            }
        }
    }

    private fun MainActivity.hasExpandedSource(position: Int?): Boolean {
        if (list.hasPendingAdapterUpdates()) return false
        val visible = list.children.filter {
            it.findViewById<ViewGroup>(R.id.flexbox).let { content -> content.isShown && content.childCount > 0 }
        }.toList()
        return if (position == null) visible.isEmpty()
        else visible.size == 1 && list.getChildAdapterPosition(visible.single()) == position &&
            visible.single().findViewById<ViewGroup>(R.id.flexbox).childCount == 9
    }

    private val MainActivity.list: RecyclerView
        get() = supportFragmentManager.fragments.filterIsInstance<ExploreFragment>().single()
            .requireView().findViewById(R.id.rv_find)

    private fun await(description: String, condition: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        do {
            var ready = false
            scenario!!.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Timed out waiting for $description")
    }

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
    }

    private fun SharedPreferences.Editor.putValue(key: String, value: Any?) {
        when (value) {
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is String -> putString(key, value)
            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            null -> remove(key)
        }
    }
}
