package io.legado.app.ui.main.explore

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.core.view.children
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
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
import io.legado.app.utils.dpToPx
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** Exercises the main discovery list, including the source's real JS refresh callback. */
@RunWith(AndroidJUnit4::class)
class ExploreRefreshUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = context.defaultSharedPreferences
    private val savedPreferences = HashMap(preferences.all)
    private val savedLocal = HashMap(LocalConfig.all)
    private val group = "Explore refresh ${UUID.randomUUID()}"
    private val source = BookSource(
        bookSourceUrl = "https://explore-refresh.invalid/$group",
        bookSourceName = "Expandable source",
        bookSourceGroup = group,
        customOrder = -100,
        jsLib = """
            function changeKind(key, value) {
                var source = this.source, java = this.java;
                var state = JSON.parse(String(source.getLoginHeader() || '{}'));
                state[key] = String(value);
                source.putLoginHeader(JSON.stringify(state));
                source.refreshExplore();
                java.refreshExplore();
            }
        """.trimIndent(),
        exploreUrl = """
            <js>
            var state = JSON.parse(String(source.getLoginHeader() || '{}'));
            var kinds = [], row = {layout_flexBasisPercent: 1};
            function label(title) { kinds.push({title: title, style: row}); }
            for (var i = 0; i < 36; i++) label('Category before controls ' + i);
            kinds.push({title:'More categories', type:'toggle', chars:['+ ', '- '],
                action:"changeKind('expanded', infoMap['More categories'])", style:row});
            kinds.push({title:'Category mode', type:'select', chars:['Short list', 'Long list'],
                action:"changeKind('mode', infoMap['Category mode'])", style:row});
            label('Rendered ' + (state.expanded || '+ ') + (state.mode || 'Short list'));
            var count = state.expanded == '- ' || state.mode == 'Long list' ? 42 : 12;
            for (var i = 0; i < count; i++) label('Category after controls ' + i);
            JSON.stringify(kinds);
            </js>
        """.trimIndent(),
    )
    private val sources = listOf(source) + (1..28).map { index ->
        BookSource(
            bookSourceUrl = "https://explore-refresh.invalid/$group/$index",
            bookSourceName = "Following source $index",
            bookSourceGroup = group,
            customOrder = index,
            exploreUrl = "Only category::https://explore-refresh.invalid/category",
        )
    }
    private var scenario: ActivityScenario<MainActivity>? = null
    private val frames = ArrayList<String>()
    private var preDraw: ViewTreeObserver.OnPreDrawListener? = null

    @Before fun setUp() {
        preferences.edit().putBoolean(PreferKey.showDiscovery, true)
            .putBoolean(PreferKey.autoRefresh, false)
            .putBoolean(PreferKey.autoCheckNewBackup, false)
            .putBoolean("autoUpdateVariant", false)
            .putString(PreferKey.defaultHomePage, "explore").commit()
        LocalConfig.edit().putBoolean("privacyPolicyOk", true)
            .putLong("appVersionCode", appInfo.versionCode).putString("password", "").commit()
        appDb.bookSourceDao.insert(*sources.toTypedArray())
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await("discovery fragment") { it.supportFragmentManager.fragments.any { fragment ->
            fragment is ExploreFragment && fragment.isResumed
        } }
        scenario!!.onActivity { activity ->
            val fragment = activity.supportFragmentManager.fragments.filterIsInstance<ExploreFragment>().single()
            fragment.requireView().findViewById<SearchView>(R.id.search_view).apply {
                setQuery("group:$group", false)
                clearFocus()
            }
        }
        await("fixture sources") { it.list.adapter?.itemCount == sources.size }
        onView(allOf(withText(source.bookSourceName), isDisplayed())).perform(click())
        await("initial categories") { it.control("Rendered + Short list") != null }
    }

    @After fun cleanUp() {
        scenario?.onActivity { activity ->
            preDraw?.let { activity.list.viewTreeObserver.removeOnPreDrawListener(it) }
        }
        scenario?.close()
        sources.forEach { fixture ->
            runBlocking { fixture.clearExploreKindsCache() }
            fixture.removeLoginHeader()
            ExploreAdapter.exploreInfoMapList.remove(fixture.bookSourceUrl)
            appDb.bookSourceDao.delete(fixture.bookSourceUrl)
        }
        preferences.edit().clear().apply {
            savedPreferences.forEach { (key, value) -> putValue(key, value) }
        }.commit()
        LocalConfig.edit().clear().apply {
            savedLocal.forEach { (key, value) -> putValue(key, value) }
        }.commit()
    }

    @Test fun internalToggleGrowsAndShrinksBelowTheVisibleControl() {
        positionControls()
        verifyRefresh("explore-toggle-expand", "+ More categories", "- More categories", "Rendered - Short list") {
            onView(allOf(withText("+ More categories"), isDisplayed())).perform(click())
        }
        verifyRefresh("explore-toggle-collapse", "- More categories", "+ More categories", "Rendered + Short list") {
            onView(allOf(withText("- More categories"), isDisplayed())).perform(click())
        }
    }

    @Test fun internalSelectGrowsAndShrinksBelowTheVisibleControl() {
        positionControls()
        for (mode in listOf("Long list", "Short list")) {
            verifyRefresh("explore-select-${mode.substringBefore(' ')}", "Category mode", "Category mode", "Rendered + $mode") {
                onView(allOf(withId(R.id.sp_type), isDisplayed())).perform(click())
                onView(allOf(withText(mode), isDisplayed())).perform(click())
            }
        }
    }

    private fun positionControls() {
        // Position the real long RecyclerView row so its source header is well above the viewport.
        scenario!!.onActivity { activity ->
            val list = activity.list
            val control = checkNotNull(activity.control("+ More categories"))
            val rectangle = Rect(0, 0, control.width, control.height)
            list.offsetDescendantRectToMyCoords(control, rectangle)
            list.scrollBy(0, rectangle.top - list.height / 3)
        }
        instrumentation.waitForIdleSync()
        scenario!!.onActivity { activity ->
            val list = activity.list
            val manager = list.layoutManager as LinearLayoutManager
            assertTrue("Expanded source header must be offscreen", manager.getDecoratedTop(checkNotNull(manager.findViewByPosition(0))) < -list.height)
            assertTrue(checkNotNull(activity.control("+ More categories")).getGlobalVisibleRect(Rect()))
            preDraw = ViewTreeObserver.OnPreDrawListener {
                frames.add(activity.describe())
                true
            }.also { list.viewTreeObserver.addOnPreDrawListener(it) }
        }
    }

    private fun verifyRefresh(name: String, beforeText: String, afterText: String, rendered: String, action: () -> Unit) {
        var beforeY = 0
        var beforeHeight = 0
        scenario!!.onActivity { activity ->
            beforeY = checkNotNull(activity.control(beforeText)).screenY()
            beforeHeight = checkNotNull(activity.list.findViewHolderForAdapterPosition(0)).itemView.height
            frames.clear()
            frames.add("before: ${activity.describe()}")
        }
        screenshot("$name-before")
        try {
            action()
            await(rendered) { it.control(rendered) != null }
            instrumentation.waitForIdleSync()
            scenario!!.onActivity { activity ->
                val control = checkNotNull(activity.control(afterText))
                assertTrue("Control left viewport: ${activity.describe()}", control.getGlobalVisibleRect(Rect()))
                assertTrue("Control jumped from $beforeY to ${control.screenY()}: ${activity.describe()}", abs(beforeY - control.screenY()) <= 2.dpToPx())
                val manager = activity.list.layoutManager as LinearLayoutManager
                assertEquals("Following sources must not replace the expanded source", 0, manager.findFirstVisibleItemPosition())
                val afterHeight = checkNotNull(manager.findViewByPosition(0)).height
                assertTrue("JS must actually change category height: before=$beforeHeight after=$afterHeight", abs(afterHeight - beforeHeight) > activity.list.height / 2)
                frames.add("after: ${activity.describe()}")
            }
        } finally {
            screenshot("$name-after")
            File(context.getExternalFilesDir("ui-regression"), "$name-frames.txt")
                .writeText(frames.joinToString("\n"))
        }
    }

    private val MainActivity.list: RecyclerView
        get() = supportFragmentManager.fragments.filterIsInstance<ExploreFragment>().single()
            .requireView().findViewById(R.id.rv_find)

    private fun MainActivity.control(text: String): TextView? =
        list.findViewHolderForAdapterPosition(0)?.itemView?.descendants()
            ?.filterIsInstance<TextView>()?.firstOrNull { it.text.toString() == text }

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) children.forEach { yieldAll(it.descendants()) }
    }

    private fun View.screenY(): Int = IntArray(2).also(::getLocationOnScreen)[1]

    private fun MainActivity.describe(): String {
        val manager = list.layoutManager as LinearLayoutManager
        val row = manager.findViewByPosition(0)
        val content = row?.findViewById<ViewGroup>(R.id.flexbox)
        return "first=${manager.findFirstVisibleItemPosition()}, top=${row?.let(manager::getDecoratedTop)}, " +
            "height=${row?.height}, children=${content?.childCount}, focus=${currentFocus?.javaClass?.simpleName}, " +
            "touch=${list.isInTouchMode}, header=${source.getLoginHeader()}"
    }

    private fun await(description: String, condition: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        do {
            var ready = false
            scenario!!.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        var state = ""
        scenario!!.onActivity { state = runCatching { it.describe() }.getOrDefault("fragment not ready") }
        throw AssertionError("Timed out waiting for $description: $state")
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
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
