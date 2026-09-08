package io.legado.app.ui.book.read

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.IntentAction
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.TTSReadAloudService
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.dpToPx
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real reader gestures; stop tests run the production service with its speech engine shut down. */
@RunWith(AndroidJUnit4::class)
class ReadAloudMenuUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val savedMenuHelp = LocalConfig.all["readMenuHelpVersion"]
    private val savedPreferences = listOf(PreferKey.readAloudControlsPause,
        PreferKey.readAloudControlsDrag, PreferKey.readAloudControlsDock,
        PreferKey.readAloudControlsX, PreferKey.readAloudControlsY,
        PreferKey.readAloudWakeLock, PreferKey.ttsTimer).associateWith { prefs.all[it] }
    private val savedRunning = BaseReadAloudService.isRun
    private val savedPaused = BaseReadAloudService.pause
    private val savedFollowing = BaseReadAloudService.followReadAloudPosition
    private var scenario: ActivityScenario<ReadBookActivity>? = null
    private var book: Book? = null
    private var textFile: File? = null
    private var serviceStarted = false
    private val notificationPermission = "android.permission.POST_NOTIFICATIONS"
    private val wasBatteryExempt = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .isIgnoringBatteryOptimizations(context.packageName)

    @Before fun setUp() {
        prefs.edit().putBoolean(PreferKey.readAloudControlsPause, true).commit()
        LocalConfig.edit().putInt("readMenuHelpVersion", 1).commit()
        val file = File.createTempFile("aloud-menu-", ".txt", context.cacheDir).also { textFile = it }
        file.writeText((0..60).joinToString("\n") { "Reader content line $it for the playback menu regression." })
        val fixture = Book(bookUrl = file.absolutePath, originName = file.name, name = file.name,
            charset = "UTF-8", type = BookType.local or BookType.text, totalChapterNum = 1)
            .apply { setPageAnim(PageAnim.noAnim) }
        book = fixture
        appDb.bookDao.insert(fixture)
        appDb.bookChapterDao.insert(BookChapter(bookUrl = fixture.bookUrl, url = "aloud-menu-chapter",
            title = "Playback controls", start = 0L, end = file.length()))
        scenario = ActivityScenario.launch(Intent(context, ReadBookActivity::class.java)
            .putExtra("bookUrl", fixture.bookUrl))
        scenario!!.onActivity { activity ->
            activity.supportFragmentManager.fragments.filterIsInstance<ClickActionConfigDialog>()
                .forEach { it.view?.findViewById<View>(R.id.iv_close)?.performClick() }
        }
        await("reader content") {
            ReadBook.book?.bookUrl == fixture.bookUrl && ReadBook.curTextChapter?.isCompleted == true &&
                !it.findViewById<ReadView>(R.id.read_view).curPage.textPage.isMsgPage && it.bottomDialog == 0
        }
    }

    @After fun tearDown() {
        if (serviceStarted) {
            context.stopService(Intent(context, TTSReadAloudService::class.java))
            await("test service destroyed") { readAloudService() == null && !BaseReadAloudService.isRun }
            if (!wasBatteryExempt) shell("dumpsys deviceidle whitelist -${context.packageName}")
        }
        instrumentation.runOnMainSync {
            playbackFlag("isRun", savedRunning)
            playbackFlag("pause", savedPaused)
            if (savedFollowing) BaseReadAloudService.restoreReadAloudFollow()
            else BaseReadAloudService.detachReadAloudFollow()
        }
        scenario?.close()
        book?.let {
            appDb.bookChapterDao.delByBook(it.bookUrl)
            appDb.bookDao.delete(it)
        }
        textFile?.delete()
        TextFile.clear()
        LocalConfig.edit().apply {
            if (savedMenuHelp == null) remove("readMenuHelpVersion")
            else putInt("readMenuHelpVersion", savedMenuHelp as Int)
        }.commit()
        prefs.edit().apply {
            savedPreferences.forEach { (key, value) ->
                when (value) {
                    null -> remove(key)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Float -> putFloat(key, value)
                }
            }
        }.commit()
    }

    @Test fun fixedPlayingControlLongPressStopsTheService() = verifyLongPressStops(paused = false, movable = false)

    @Test fun fixedPausedControlLongPressStopsTheService() = verifyLongPressStops(paused = true, movable = false)

    @Test fun movablePlayingControlLongPressStopsTheService() = verifyLongPressStops(paused = false, movable = true)

    @Test fun movablePausedControlLongPressStopsTheService() = verifyLongPressStops(paused = true, movable = true)

    private fun verifyLongPressStops(paused: Boolean, movable: Boolean) {
        serviceStarted = true
        // Grant for this disposable instrumentation session; revocation kills the target process.
        shell("pm grant ${context.packageName} $notificationPermission")
        shell("dumpsys deviceidle whitelist +${context.packageName}")
        scenario!!.onActivity { activity ->
            prefs.edit().putBoolean(PreferKey.readAloudControlsDrag, movable)
                .putBoolean(PreferKey.readAloudControlsDock, false)
                .putFloat(PreferKey.readAloudControlsX, .5f)
                .putFloat(PreferKey.readAloudControlsY, .7f)
                .putBoolean(PreferKey.readAloudWakeLock, false)
                .putInt(PreferKey.ttsTimer, 0).commit()
            ReadBook.book!!.setTtsEngine("")
            ReadAloud.upReadAloudClass()
            activity.startService(Intent(activity, TTSReadAloudService::class.java).setAction(IntentAction.pause))
        }
        await("real paused service starts") { readAloudService() != null && BaseReadAloudService.isRun && BaseReadAloudService.pause }
        var service: TTSReadAloudService? = null
        scenario!!.onActivity { activity ->
            service = checkNotNull(readAloudService())
            // Keep the real service lifecycle/commands; voice availability is outside this gesture test.
            service!!.clearTTS()
            if (!paused) ReadAloud.resume(activity)
            activity.showReadAloudControls()
        }
        await("pause control and requested playback state") {
            BaseReadAloudService.isRun && BaseReadAloudService.pause == paused &&
                it.findViewById<View>(R.id.iv_pause_aloud).isShown
        }
        onView(withId(R.id.iv_pause_aloud)).perform(click())
        await("short tap changes pause state") { BaseReadAloudService.isRun && BaseReadAloudService.pause != paused }
        onView(withId(R.id.iv_pause_aloud)).perform(click())
        await("second tap restores pause state") { BaseReadAloudService.isRun && BaseReadAloudService.pause == paused }
        if (movable) {
            var beforeY = 0f
            scenario!!.onActivity { beforeY = it.findViewById<View>(R.id.read_aloud_float_bar_container).y }
            // A slow drag lasts beyond the long-press timeout and must not stop or toggle playback.
            onView(withId(R.id.iv_pause_aloud)).perform(GeneralSwipeAction(Swipe.SLOW,
                GeneralLocation.CENTER, { view ->
                    GeneralLocation.CENTER.calculateCoordinates(view).also { it[1] -= 96.dpToPx() }
                }, Press.FINGER))
            scenario!!.onActivity {
                assertTrue("Drag moves the control", it.findViewById<View>(R.id.read_aloud_float_bar_container).y < beforeY - 48.dpToPx())
                assertTrue("Dragging must keep the service alive", BaseReadAloudService.isRun)
                assertEquals("Dragging must not toggle playback", paused, BaseReadAloudService.pause)
                assertTrue("Drag stores the new position", prefs.getFloat(PreferKey.readAloudControlsY, .7f) < .7f)
            }
        }
        val label = "aloud-stop-paused-$paused-movable-$movable"
        screenshot("$label-before")
        try {
            onView(withId(R.id.iv_pause_aloud)).perform(longClick())
            await("long press destroys service (paused=$paused, movable=$movable)", 5000) {
                !BaseReadAloudService.isRun && readAloudService() == null
            }
            scenario!!.onActivity {
                assertEquals(Lifecycle.State.DESTROYED, service!!.lifecycle.currentState)
                assertTrue("Stopped service remains paused", BaseReadAloudService.pause)
                assertFalse("Stopped controls disappear", it.findViewById<View>(R.id.read_aloud_float_bar_container).isVisible)
            }
        } finally {
            screenshot("$label-after")
            File(context.getExternalFilesDir("ui-regression"), "$label-state.txt").writeText(
                "running=${BaseReadAloudService.isRun}, paused=${BaseReadAloudService.pause}, lifecycle=${service!!.lifecycle.currentState}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readAloudService(): TTSReadAloudService? {
        val services = LifecycleHelp::class.java.getDeclaredField("services").apply { isAccessible = true }
            .get(LifecycleHelp) as List<WeakReference<*>>
        return services.mapNotNull { it.get() }.filterIsInstance<TTSReadAloudService>().singleOrNull()
    }

    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
    }

    @Test fun returningFromAloudDialogKeepsControlsHiddenUntilMainMenuCloses() {
        for (paused in listOf(false, true)) {
            scenario!!.onActivity { activity ->
                playbackFlag("isRun", true)
                playbackFlag("pause", paused)
                BaseReadAloudService.restoreReadAloudFollow()
                activity.showReadAloudControls()
            }
            await("pause control visible") { it.findViewById<View>(R.id.iv_pause_aloud).isShown }
            scenario!!.onActivity { it.showReadAloudDialog() }
            await("aloud dialog visible") { it.bottomDialog == 1 }
            scenario!!.onActivity { assertFalse(it.findViewById<View>(R.id.read_aloud_float_bar_container).isVisible) }
            onView(withId(R.id.ll_main_menu)).inRoot(isDialog()).perform(click())
            await("returned to main menu") { it.bottomDialog == 0 && it.findViewById<ReadMenu>(R.id.read_menu).isVisible }
            screenshot("aloud-main-menu-paused-$paused")
            scenario!!.onActivity {
                assertFalse("Playback controls must remain hidden over the main menu",
                    it.findViewById<View>(R.id.read_aloud_float_bar_container).isVisible)
                it.findViewById<ReadMenu>(R.id.read_menu).runMenuOut(anim = false)
            }
            await("controls return after menu closes") { it.findViewById<View>(R.id.iv_pause_aloud).isShown }
        }
    }

    private fun playbackFlag(name: String, value: Boolean) {
        BaseReadAloudService::class.java.getDeclaredField(name).apply { isAccessible = true }
            .setBoolean(null, value)
    }

    private fun await(description: String, timeoutMillis: Long = 30000, condition: (ReadBookActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        do {
            var ready = false
            scenario!!.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Timed out waiting for $description")
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val rendered = CountDownLatch(1)
        scenario!!.onActivity {
            val decor = it.window.decorView
            decor.postOnAnimation { decor.postOnAnimation { rendered.countDown() } }
        }
        assertTrue(rendered.await(5, TimeUnit.SECONDS))
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}
