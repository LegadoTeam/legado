package io.legado.app.ui.highlight

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inspector.WindowInspector
import android.widget.ListView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.hasSibling
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.HighlightRuleFile
import io.legado.app.databinding.ItemHighlightRuleBinding
import io.legado.app.help.IntentData
import io.legado.app.ui.file.HandleFileActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.TitleBar
import io.legado.app.utils.GSON
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class HighlightGroupUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dao = appDb.highlightRuleDao
    private val namedUngrouped = context.getString(R.string.no_group)
    private var savedRules = emptyList<HighlightRule>()
    private var scenario: ActivityScenario<HighlightRuleActivity>? = null
    private val fixtures = listOf(
        HighlightRule(name = "Alice", pattern = "Alice", group = "Characters", order = 0),
        HighlightRule(name = "Bob", pattern = "Bob", group = "Characters", order = 1),
        HighlightRule(name = "Quote", pattern = "Quote", group = "Quotes", order = 2),
        HighlightRule(name = "Named group", pattern = "Named", group = namedUngrouped, order = 3),
        HighlightRule(name = "Loose rule", pattern = "Loose", order = 4),
    )

    @Before fun setUp() {
        savedRules = dao.all
        dao.deleteAll()
        dao.insert(*fixtures.toTypedArray())
        scenario = ActivityScenario.launch(HighlightRuleActivity::class.java)
        awaitRules(dao.all)
    }

    @After fun cleanUp() {
        scenario?.close()
        dao.deleteAll()
        if (savedRules.isNotEmpty()) dao.insert(*savedRules.toTypedArray())
    }

    @Test fun filterRenameMoveAndDeleteUseRealDialogsAndPreserveOtherRules() {
        onView(withContentDescription(androidx.appcompat.R.string.abc_action_menu_overflow_description))
            .perform(click())
        instrumentation.waitForIdleSync()
        val menuBitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "highlight-more-menu.png")
                .outputStream().use { assertTrue(menuBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { menuBitmap.recycle() }
        pressBack()
        filter("[Characters]")
        awaitRules(dao.all.filter { it.group == "Characters" })
        screenshot("highlight-group-filter")

        menu(R.id.menu_highlight_group_manage)
        groupAction("Characters", R.id.tv_edit)
        onView(withId(R.id.edit_view)).inRoot(isDialog())
            .perform(replaceText("People"), closeSoftKeyboard())
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
        awaitGroup("People")
        screenshot("highlight-group-manager")
        pressBack()
        // Renaming the active group must not leave a stale, empty filter behind.
        awaitRules(dao.all)

        filter("[People]")
        awaitRules(dao.all.filter { it.group == "People" })
        menu(R.id.menu_highlight_group_manage)
        groupAction("People", R.id.tv_del)
        onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
        // This is a real group named like the special ungrouped option.
        choose("[$namedUngrouped]")
        await { dao.all.count { it.group == namedUngrouped } == 3 }
        assertEquals("Quotes", dao.all.single { it.name == "Quote" }.group)
        assertNull(dao.all.single { it.name == "Loose rule" }.group)
        pressBack()
        awaitRules(dao.all)

        filter("[$namedUngrouped]")
        awaitRules(dao.all.filter { it.group == namedUngrouped })
        menu(R.id.menu_highlight_group_manage)
        groupAction(namedUngrouped, R.id.tv_del)
        onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
        choose(context.getString(R.string.no_group))
        await { dao.all.count { it.group == null } == 4 }
        pressBack()
        awaitRules(dao.all)
        filter(context.getString(R.string.no_group))
        awaitRules(dao.all.filter { it.group == null })

        filter("[Quotes]")
        awaitRules(dao.all.filter { it.group == "Quotes" })
        menu(R.id.menu_highlight_group_manage)
        groupAction("Quotes", R.id.tv_del)
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
        await { dao.all.size == 4 }
        assertEquals(fixtures.filter { it.group != "Quotes" }.map { it.uuid }.toSet(),
            dao.all.map { it.uuid }.toSet())
        pressBack()
        awaitRules(dao.all)
        screenshot("highlight-group-after-move-delete")
    }

    @Test fun exportAllIncludesRulesHiddenByGroupFilter() {
        filter("[Characters]")
        awaitRules(dao.all.filter { it.group == "Characters" })
        val launched = AtomicReference<Intent?>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className != HandleFileActivity::class.java.name) return null
                launched.set(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            menu(R.id.menu_export_all)
            await { launched.get() != null }
            val intent = launched.get()!!
            assertEquals(HandleFileContract.EXPORT, intent.getIntExtra("mode", -1))
            assertEquals("HighlightRules.json", intent.getStringExtra("fileName"))
            val data = checkNotNull(IntentData.get<ByteArray>(intent.getStringExtra("fileKey")))
            val exported = GSON.fromJson(data.toString(Charsets.UTF_8), HighlightRuleFile::class.java)
            assertEquals(HighlightRuleFile.TYPE, exported.type)
            assertEquals(fixtures.map { it.uuid }.toSet(), exported.rules!!.map { it!!.uuid }.toSet())
            assertEquals(5, exported.rules!!.size)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun importingGroupAndEnabledChangesRefreshesBothVisibleFields() {
        val changed = dao.all.first().copy(group = "Updated", isEnabled = false)
        dao.importRules(listOf(changed))
        awaitRules(dao.all)
        screenshot("highlight-group-import-refresh")
    }

    private fun menu(id: Int) {
        scenario!!.onActivity {
            assertTrue(it.findViewById<TitleBar>(R.id.title_bar).menu.performIdentifierAction(id, 0))
        }
    }

    private fun filter(label: String) {
        menu(R.id.menu_highlight_group_filter)
        choose(label)
    }

    private fun choose(label: String) {
        fun hasChoice(view: View): Boolean {
            if (view is ListView && (0 until view.count).any { view.getItemAtPosition(it).toString() == label }) {
                return true
            }
            return view is ViewGroup && (0 until view.childCount).any { hasChoice(view.getChildAt(it)) }
        }
        // Room's first emission is asynchronous; wait for the actual focused list dialog.
        await {
            var ready = false
            instrumentation.runOnMainSync {
                ready = WindowInspector.getGlobalWindowViews().any { it.hasWindowFocus() && hasChoice(it) }
            }
            ready
        }
        onView(withText(label)).inRoot(isDialog()).perform(click())
    }

    private fun groupAction(group: String, action: Int) {
        awaitGroup(group)
        onView(allOf(withId(action), hasSibling(withText(group)))).inRoot(isDialog()).perform(click())
    }

    private fun awaitGroup(group: String) = await {
        var visible = false
        scenario!!.onActivity { activity ->
            val recycler = groupDialog(activity)?.view?.findViewById<RecyclerView>(R.id.recycler_view)
            visible = recycler != null && !recycler.hasPendingAdapterUpdates() &&
                (0 until recycler.childCount).any {
                    recycler.getChildAt(it).findViewById<TextView>(R.id.tv_group).text.toString() == group
                }
        }
        visible
    }

    private fun awaitRules(expected: List<HighlightRule>) = await {
        var rendered = false
        scenario!!.onActivity { activity ->
            val recycler = activity.findViewById<RecyclerView>(R.id.recycler_view)
            val adapter = recycler.adapter as HighlightRuleAdapter
            rendered = adapter.getItems().map { it.uuid } == expected.map { it.uuid } &&
                !recycler.isComputingLayout && !recycler.hasPendingAdapterUpdates() &&
                !recycler.isLayoutRequested && recycler.itemAnimator?.isRunning != true &&
                recycler.childCount == expected.size && (0 until recycler.childCount).all { index ->
                    val binding = ItemHighlightRuleBinding.bind(recycler.getChildAt(index))
                    val rule = expected[index]
                    val label = rule.group?.takeIf { it.isNotBlank() }?.let { "[$it] ${rule.getDisplayName()}" }
                        ?: rule.getDisplayName()
                    binding.cbName.text.toString() == label && binding.swtEnabled.isChecked == rule.isEnabled
                }
        }
        rendered
    }

    private fun groupDialog(activity: HighlightRuleActivity) =
        activity.supportFragmentManager.fragments.filterIsInstance<HighlightGroupManageDialog>().firstOrNull()

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("Highlight groups did not reach the expected state", condition())
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val frame = CountDownLatch(1)
        lateinit var window: Window
        lateinit var bitmap: Bitmap
        scenario!!.onActivity { activity ->
            window = groupDialog(activity)?.dialog?.window ?: activity.window
            val decor = window.decorView
            assertTrue(decor.isHardwareAccelerated)
            bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            decor.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }
            decor.postInvalidateOnAnimation()
        }
        try {
            assertTrue("Highlight frame was not committed", frame.await(5, TimeUnit.SECONDS))
            val copied = CountDownLatch(1)
            var result = PixelCopy.ERROR_UNKNOWN
            instrumentation.runOnMainSync {
                PixelCopy.request(window, bitmap, { result = it; copied.countDown() }, Handler(Looper.getMainLooper()))
            }
            assertTrue(copied.await(5, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, result)
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}
