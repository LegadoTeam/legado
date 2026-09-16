package io.legado.app.ui.highlight

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.ClipboardManager
import android.content.Context
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
import android.widget.NumberPicker
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.pressBack as backAction
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.hasSibling
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.HighlightRuleFile
import io.legado.app.databinding.ItemHighlightRuleBinding
import io.legado.app.help.IntentData
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.HighlightStyle
import io.legado.app.ui.file.HandleFileActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.TitleBar
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URL
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
        HighlightRule(name = "Alice", pattern = "Alice", group = "Characters", order = 0).apply {
            applyStyle(HighlightStyle(fill = 0xFF209050.toInt(),
                fillShape = HighlightStyle.FillShape.PILL, pillPaddingScale = 1.25f))
        },
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

    @Test fun fontSizeAndNegativeSpacingPersistAndResetThroughTheActualStyleDialog() {
        fun openStyle() {
            onView(allOf(withId(R.id.iv_edit), hasSibling(withText("[Characters] Alice")))).perform(click())
            await {
                var loaded = false
                instrumentation.runOnMainSync {
                    loaded = WindowInspector.getGlobalWindowViews().any {
                        it.hasWindowFocus() && it.findViewById<View>(R.id.btn_ok)?.isEnabled == true
                    }
                }
                loaded
            }
            onView(withId(R.id.btn_style)).inRoot(isDialog()).perform(click())
            instrumentation.runOnMainSync {
                val sheet = WindowInspector.getGlobalWindowViews().single { it.hasWindowFocus() }
                    .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet).state =
                    com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            }
            await {
                var expanded = false
                instrumentation.runOnMainSync {
                    val sheet = WindowInspector.getGlobalWindowViews().single { it.hasWindowFocus() }
                        .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                    expanded = com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet).state ==
                        com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                }
                expanded
            }
        }
        fun edit(id: Int, value: Int?) {
            onView(withId(id)).inRoot(isDialog()).perform(scrollTo(), click())
            if (value != null) instrumentation.runOnMainSync {
                val picker = WindowInspector.getGlobalWindowViews().single { it.hasWindowFocus() }
                    .findViewById<NumberPicker>(R.id.number_picker)
                picker.value = value
                if (id == R.id.tv_highlight_letter_spacing) assertEquals("-20%", picker.displayedValues[value])
            }
            onView(withId(if (value == null) android.R.id.button3 else android.R.id.button1))
                .inRoot(isDialog()).perform(click())
        }
        fun save() {
            onView(withId(R.id.tv_highlight_font_size)).inRoot(isDialog()).perform(backAction())
            onView(withId(R.id.btn_ok)).inRoot(isDialog()).perform(click())
        }
        openStyle()
        edit(R.id.tv_highlight_font_size, 42)
        edit(R.id.tv_highlight_letter_spacing, 30)
        edit(R.id.tv_highlight_horizontal_padding, 12)
        screenshot("highlight-font-metrics-settings")
        save()
        await { dao.all.first().styleObj().let {
            it.fontSize == 42f && it.letterSpacing == -0.2f && it.horizontalPadding == 12f && it.pillPaddingScale == 1.25f
        } }
        scenario!!.recreate()
        awaitRules(dao.all)
        openStyle()
        onView(withId(R.id.tv_highlight_font_size)).inRoot(isDialog()).perform(scrollTo())
            .check(matches(withText(context.getString(R.string.text_size) + " · 42")))
        onView(withId(R.id.tv_highlight_horizontal_padding)).inRoot(isDialog()).perform(scrollTo())
            .check(matches(withText(context.getString(R.string.highlight_horizontal_padding) + " · 12 dp")))
        edit(R.id.tv_highlight_font_size, null)
        edit(R.id.tv_highlight_letter_spacing, null)
        edit(R.id.tv_highlight_horizontal_padding, null)
        save()
        await { dao.all.first().styleObj().let {
            it.fontSize == null && it.letterSpacing == null && it.horizontalPadding == null && it.pillPaddingScale == 1.25f
        } }
    }

    @Test fun pillMarginEditsPersistAndResetThroughTheActualStyleDialog() {
        fun margin(value: Int) = context.getString(R.string.highlight_pill_padding_value, value)
        fun openStyle() {
            onView(allOf(withId(R.id.iv_edit), hasSibling(withText("[Characters] Alice"))))
                .perform(click())
            await {
                var loaded = false
                instrumentation.runOnMainSync {
                    loaded = WindowInspector.getGlobalWindowViews().any {
                        it.hasWindowFocus() && it.findViewById<View>(R.id.btn_ok)?.isEnabled == true
                    }
                }
                loaded
            }
            onView(withId(R.id.btn_style)).inRoot(isDialog()).perform(click())
        }
        fun saveStyle() {
            onView(withText(R.string.highlight_bg_color)).inRoot(isDialog()).perform(backAction())
            onView(withId(R.id.btn_ok)).inRoot(isDialog()).perform(click())
        }
        openStyle()
        onView(withText(margin(125))).inRoot(isDialog()).perform(click())
        instrumentation.runOnMainSync {
            val picker = WindowInspector.getGlobalWindowViews().single { it.hasWindowFocus() }
                .findViewById<NumberPicker>(R.id.number_picker)
            assertEquals(25, picker.minValue)
            assertEquals(200, picker.maxValue)
            picker.value = 150
        }
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
        onView(withText(margin(150))).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withText(R.string.highlight_bg_color)).inRoot(isDialog()).perform(click(), click())
        onView(withText(margin(150))).inRoot(isDialog()).check(matches(isDisplayed()))
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "highlight-pill-margin-settings.png")
                .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        saveStyle()
        await { dao.all.first().styleObj().resolvedPillPaddingScale == 1.5f }
        scenario!!.recreate()
        awaitRules(dao.all)
        openStyle()
        onView(withText(margin(150))).inRoot(isDialog()).perform(click())
        onView(withId(android.R.id.button3)).inRoot(isDialog()).perform(click())
        onView(withText(margin(100))).inRoot(isDialog()).check(matches(isDisplayed()))
        saveStyle()
        await { dao.all.first().styleObj().pillPaddingScale == null }
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
            onView(withText(R.string.export_success)).check(doesNotExist())
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun selectedExportUploadsOnlyCheckedRulesAndShowsACopyableDownloadLink() {
        val uploaded = AtomicReference<String?>()
        val uploadedName = AtomicReference<String?>()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                if (session.method == Method.POST && session.uri == "/upload") {
                    val files = hashMapOf<String, String>()
                    session.parseBody(files)
                    uploaded.set(File(checkNotNull(files["file"])).readText())
                    uploadedName.set(session.parameters["file"]?.single())
                    return newFixedLengthResponse(Response.Status.OK, "application/json",
                        """{"url":"http://127.0.0.1:$listeningPort/HighlightRules.json"}""")
                }
                return newFixedLengthResponse(Response.Status.OK, "application/json", uploaded.get().orEmpty())
            }
        }
        val previousRule = DirectLinkUpload.getConfig()
        val preferences = context.defaultSharedPreferences
        val previousCronet = preferences.all[PreferKey.cronet] as Boolean?
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var previousClip: android.content.ClipData? = null
        scenario!!.onActivity { previousClip = clipboard.primaryClip }
        server.start()
        try {
            preferences.edit().putBoolean(PreferKey.cronet, false).commit()
            val url = "http://127.0.0.1:${server.listeningPort}/HighlightRules.json"
            val summary = "Local export regression server"
            DirectLinkUpload.putConfig(DirectLinkUpload.Rule(
                uploadUrl = "http://127.0.0.1:${server.listeningPort}/upload," +
                    """{"method":"POST","body":{"file":"fileRequest"},"type":"multipart/form-data"}""",
                downloadUrlRule = "$.url", summary = summary,
            ))
            val expected = dao.all.first()
            onView(allOf(withId(R.id.cb_name), withText("[Characters] Alice"))).perform(click())
            onView(allOf(withId(R.id.iv_menu_more), isDescendantOfA(withId(R.id.select_action_bar))))
                .perform(click())
            onView(withText(R.string.export_selection)).perform(click())
            onView(withText(R.string.upload_url)).inRoot(isDialog()).perform(click())
            await {
                var shown = false
                instrumentation.runOnMainSync {
                    shown = WindowInspector.getGlobalWindowViews().any {
                        it.hasWindowFocus() && it.findViewById<TextView>(R.id.edit_view)?.text?.toString() == url
                    }
                }
                shown
            }
            onView(withText(R.string.export_success)).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText(summary)).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withId(R.id.edit_view)).inRoot(isDialog()).check(matches(withText(url)))
            assertEquals("HighlightRules.json", uploadedName.get())
            val downloaded = URL(url).readText()
            assertEquals(uploaded.get(), downloaded)
            val exported = GSON.fromJson(downloaded, HighlightRuleFile::class.java)
            assertEquals(HighlightRuleFile.TYPE, exported.type)
            assertEquals(GSON.toJson(expected), GSON.toJson(exported.rules!!.single()))
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                File(context.getExternalFilesDir("ui-regression"), "highlight-export-success.png")
                    .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
            scenario!!.onActivity { assertEquals(url, clipboard.primaryClip?.getItemAt(0)?.text?.toString()) }
        } finally {
            if (previousRule == null) DirectLinkUpload.delConfig() else DirectLinkUpload.putConfig(previousRule)
            preferences.edit().apply {
                if (previousCronet == null) remove(PreferKey.cronet) else putBoolean(PreferKey.cronet, previousCronet)
            }.commit()
            scenario!!.onActivity {
                previousClip?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip()
            }
            server.stop()
            // Clearing the clipboard leaves SystemUI's preview covering later tests' touch targets.
            instrumentation.uiAutomation.executeShellCommand(
                "am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS"
            ).use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use {
                    assertTrue(it.readText().contains("Broadcast completed"))
                }
            }
            instrumentation.uiAutomation.waitForIdle(200, 5_000)
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
            fun focusedWindow(fragment: androidx.fragment.app.Fragment): Window? {
                if (!fragment.isAdded) return null
                return (fragment as? androidx.fragment.app.DialogFragment)?.dialog?.window
                    ?.takeIf { it.decorView.hasWindowFocus() }
                    ?: fragment.childFragmentManager.fragments.firstNotNullOfOrNull(::focusedWindow)
            }
            window = activity.supportFragmentManager.fragments.firstNotNullOfOrNull(::focusedWindow)
                ?: activity.window
            if (name == "highlight-font-metrics-settings") assertTrue(window !== activity.window)
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
