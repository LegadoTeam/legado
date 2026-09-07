package io.legado.app.ui.book.read

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.preference.Preference
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.Restore
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.generateSilentWavBytes
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.read.config.ReadAloudConfigDialog
import io.legado.app.ui.book.read.config.ReadAloudControlsDialog
import io.legado.app.ui.book.read.config.ReadAloudDialog
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class ReadAloudControlsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val keys = listOf(PreferKey.readAloudControlsPause, PreferKey.readAloudControlsAutoHide,
        PreferKey.readAloudControlsDrag, PreferKey.readAloudControlsDock, PreferKey.readAloudControlsSize,
        PreferKey.readAloudControlsOpacity, PreferKey.readAloudControlsThreshold,
        PreferKey.readAloudControlsX, PreferKey.readAloudControlsY, PreferKey.cronet,
        PreferKey.readAloudFollowManualPage)
    private val savedPrefs = keys.associateWith { prefs.all[it] }
    private val savedBackup = HashMap(BackupConfig.ignoreConfig)
    private val requests = AtomicInteger()
    private val wav = generateSilentWavBytes(10_000)
    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            requests.incrementAndGet()
            return newFixedLengthResponse(Response.Status.OK, "audio/wav", wav.inputStream(), wav.size.toLong())
        }
    }
    private lateinit var book: Book
    private lateinit var file: File
    private lateinit var engine: HttpTTS
    private var scenario: ActivityScenario<ReadBookActivity>? = null
    private var archive: File? = null

    @Before fun setUp() {
        prefs.edit().apply { keys.forEach { remove(it) } }.commit()
        server.start()
        engine = HttpTTS(name = "Controls fixture", url = "http://127.0.0.1:${server.listeningPort}/audio")
        appDb.httpTTSDao.insert(engine)
        file = File.createTempFile("aloud-controls-", ".txt", context.cacheDir)
        file.writeText((0..400).joinToString("\n") {
            "Line $it. Local reader text for playback controls and scroll navigation."
        })
        book = Book(bookUrl = file.absolutePath, originName = file.name, name = file.name,
            charset = "UTF-8", type = BookType.local or BookType.text, totalChapterNum = 1)
        book.setTtsEngine(engine.id.toString())
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(BookChapter(bookUrl = book.bookUrl, url = "aloud-controls",
            title = "Playback controls", start = 0L, end = file.length()))
    }

    @After fun tearDown() {
        instrumentation.runOnMainSync { ReadAloud.stop(context) }
        val deadline = SystemClock.uptimeMillis() + 5000
        while (BaseReadAloudService.isRun && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        scenario?.close()
        server.stop()
        TextFile.clear()
        if (::book.isInitialized) appDb.bookDao.delete(book)
        if (::engine.isInitialized) appDb.httpTTSDao.delete(engine)
        if (::file.isInitialized) file.delete()
        archive?.parentFile?.deleteRecursively()
        BackupConfig.ignoreConfig.clear()
        BackupConfig.ignoreConfig.putAll(savedBackup)
        prefs.edit().apply {
            savedPrefs.forEach { (key, value) -> when (value) {
                null -> remove(key)
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Float -> putFloat(key, value)
            } }
        }.commit()
    }

    @Test fun realPlaybackPauseResumeMenusAndDetachedActionsAreExclusive() {
        launchReader(PageAnim.noAnim)
        startSpeech()
        scenario!!.onActivity { assertFalse(it.bar.isVisible) }
        prefs.edit().putBoolean(PreferKey.readAloudControlsPause, true).commit()
        await { it.pause.isShown }
        scenario!!.onActivity {
            assertFalse(it.findViewById<View>(R.id.ll_back_to_speech).isVisible)
            assertTrue(it.pause.width >= 48 * it.resources.displayMetrics.density)
            it.pause.performClick()
        }
        await { BaseReadAloudService.pause && it.pause.contentDescription == it.getString(R.string.resume) }
        scenario!!.onActivity { it.pause.performClick() }
        await { BaseReadAloudService.isPlay() && it.pause.contentDescription == it.getString(R.string.pause) }
        screenshot("aloud-controls-pause-portrait")
        scenario!!.onActivity { ReadAloudDialog().show(it.supportFragmentManager, "aloud-test") }
        await { it.bottomDialog > 0 && !it.bar.isVisible }
        scenario!!.onActivity {
            (it.supportFragmentManager.findFragmentByTag("aloud-test") as ReadAloudDialog).dismiss()
        }
        await { it.bottomDialog == 0 && it.pause.isShown }
        scenario!!.onActivity { assertTrue(ReadBook.moveToNextPage()) }
        await { !ReadAloud.followReadAloudPosition && it.bar.isShown && !it.pause.isVisible }
        scenario!!.onActivity {
            assertTrue(it.findViewById<View>(R.id.ll_back_to_speech).isShown)
            assertTrue(it.findViewById<View>(R.id.ll_read_from_here).isShown)
            it.findViewById<View>(R.id.ll_back_to_speech).performClick()
        }
        await { ReadAloud.followReadAloudPosition && it.pause.isShown }
        scenario!!.onActivity { ReadBook.moveToNextPage() }
        await { !ReadAloud.followReadAloudPosition && !it.pause.isVisible }
        scenario!!.onActivity { it.findViewById<View>(R.id.ll_read_from_here).performClick() }
        await { ReadAloud.followReadAloudPosition && it.pause.isShown }
        assertTrue("The real HTTP speech engine fetched audio", requests.get() > 0)
    }

    @Test fun actualScrollHidesAtThresholdAndMenuOrResetRevealsControls() {
        launchReader(PageAnim.scrollPageAnim)
        prefs.edit().putBoolean(PreferKey.readAloudControlsPause, true)
            .putBoolean(PreferKey.readAloudControlsAutoHide, true)
            .putInt(PreferKey.readAloudControlsThreshold, 50).commit()
        startSpeech()
        await { it.pause.isShown }
        scenario!!.onActivity { it.reader.curPage.scroll(-ChapterProvider.visibleHeight / 4) }
        await { !ReadAloud.followReadAloudPosition && it.bar.isShown && !it.pause.isVisible }
        scenario!!.onActivity { it.reader.curPage.scroll(-ChapterProvider.visibleHeight / 3) }
        await { !it.bar.isVisible }
        scenario!!.onActivity { it.onMenuShow(); it.onMenuHide() }
        await { it.bar.isShown }
        scenario!!.onActivity { it.reader.curPage.scroll(-ChapterProvider.visibleHeight) }
        await { !it.bar.isVisible }
        scenario!!.recreate()
        await { !it.bar.isVisible && it.bottomDialog == 0 }
        scenario!!.onActivity { it.showReadAloudControls() }
        await { it.bar.isShown }
        prefs.edit().putBoolean(PreferKey.readAloudControlsAutoHide, false).commit()
        scenario!!.onActivity { it.reader.curPage.scroll(-ChapterProvider.visibleHeight) }
        await { it.bar.isShown }
        screenshot("aloud-controls-position-actions")
    }

    @Test fun nestedSettingsDragDockRotationAndResetKeepTouchTargetsReachable() {
        launchReader(PageAnim.noAnim)
        startSpeech()
        scenario!!.onActivity { ReadAloudConfigDialog().show(it.supportFragmentManager, "settings-test") }
        await {
            val dialog = it.supportFragmentManager.findFragmentByTag("settings-test") as? ReadAloudConfigDialog
            dialog?.childFragmentManager?.fragments?.isNotEmpty() == true
        }
        scenario!!.onActivity {
            val dialog = it.supportFragmentManager.findFragmentByTag("settings-test") as ReadAloudConfigDialog
            val fragment = dialog.childFragmentManager.fragments.single() as ReadAloudConfigDialog.ReadAloudPreferenceFragment
            val entry = checkNotNull(fragment.findPreference<Preference>("readAloudControls"))
            assertEquals(it.getString(R.string.read_aloud_controls), entry.title)
            fragment.onPreferenceTreeClick(entry)
        }
        await { controlsFragment(it) != null }
        scenario!!.onActivity {
            val fragment = checkNotNull(controlsFragment(it))
            listOf(PreferKey.readAloudControlsPause, PreferKey.readAloudControlsAutoHide,
                PreferKey.readAloudControlsDrag, PreferKey.readAloudControlsDock).forEach { key ->
                val preference = checkNotNull(fragment.findPreference<androidx.preference.SwitchPreferenceCompat>(key))
                assertFalse(preference.isChecked)
                if (key != PreferKey.readAloudControlsAutoHide) preference.isChecked = true
            }
            fragment.findPreference<androidx.preference.SeekBarPreference>(PreferKey.readAloudControlsSize)!!.value = 60
            fragment.findPreference<androidx.preference.SeekBarPreference>(PreferKey.readAloudControlsOpacity)!!.value = 20
        }
        screenshot("aloud-controls-settings")
        scenario!!.onActivity {
            controlsDialog(it)!!.dismiss()
            (it.supportFragmentManager.findFragmentByTag("settings-test") as ReadAloudConfigDialog).dismiss()
        }
        await { it.pause.isShown && it.pause.height == (60 * it.resources.displayMetrics.density).toInt() }
        scenario!!.onActivity { activity ->
            assertEquals(51, Color.alpha((activity.bar.background as GradientDrawable).color!!.defaultColor))
            drag(activity.pause, -activity.reader.width.toFloat(), -activity.reader.height / 3f)
        }
        await { prefs.contains(PreferKey.readAloudControlsX) && it.bar.x <= 1f }
        val savedY = prefs.getFloat(PreferKey.readAloudControlsY, -1f)
        assertTrue(savedY >= 0f)
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        await { it.reader.width > it.reader.height && it.bar.isShown }
        instrumentation.waitForIdleSync()
        scenario!!.onActivity {
            assertTrue(it.bar.x >= 0f && it.bar.y >= 0f)
            assertTrue(it.bar.x + it.bar.width <= it.reader.width + 1f)
            assertTrue(it.bar.y + it.bar.height <= it.reader.height + 1f)
            assertTrue(it.pause.width >= 48 * it.resources.displayMetrics.density)
        }
        screenshot("aloud-controls-pause-landscape")
        scenario!!.recreate()
        await { it.pause.isShown }
        assertEquals(savedY, prefs.getFloat(PreferKey.readAloudControlsY, -1f))
        scenario!!.onActivity { it.showReadAloudControls(resetPosition = true) }
        await { !prefs.contains(PreferKey.readAloudControlsX) && !prefs.contains(PreferKey.readAloudControlsY) }
    }

    @Test fun actualBackupArchiveRestoresAllControlPreferences() = runBlocking {
        BackupConfig.contentKeys.forEach { BackupConfig.ignoreConfig[it] = it != BackupConfig.settingContentKey }
        prefs.edit().putBoolean(PreferKey.readAloudControlsPause, true)
            .putBoolean(PreferKey.readAloudControlsAutoHide, true)
            .putBoolean(PreferKey.readAloudControlsDrag, true).putBoolean(PreferKey.readAloudControlsDock, true)
            .putInt(PreferKey.readAloudControlsSize, 64).putInt(PreferKey.readAloudControlsOpacity, 25)
            .putInt(PreferKey.readAloudControlsThreshold, 150).putFloat(PreferKey.readAloudControlsX, .2f)
            .putFloat(PreferKey.readAloudControlsY, .3f).commit()
        val expected = keys.filter { it.startsWith("readAloudControls") }.associateWith { prefs.all[it] }
        val backup = Backup.backupForLanTransferLocked(context).also { archive = it }
        ZipFile(backup).use { zip ->
            val xml = zip.getInputStream(checkNotNull(zip.getEntry("config.xml"))).bufferedReader().use { it.readText() }
            expected.keys.forEach { assertTrue("Archive must contain $it", xml.contains(it)) }
        }
        prefs.edit().apply { expected.keys.forEach { remove(it) } }.commit()
        Restore.restoreOrThrow(context, backup.toUri(), lanTransfer = true)
        expected.forEach { (key, value) -> assertEquals(key, value, prefs.all[key]) }
    }

    private fun launchReader(pageAnim: Int) {
        book.setPageAnim(pageAnim)
        appDb.bookDao.update(book)
        scenario = ActivityScenario.launch(Intent(context, ReadBookActivity::class.java).putExtra("bookUrl", book.bookUrl))
        scenario!!.onActivity { activity ->
            activity.supportFragmentManager.fragments.filterIsInstance<ClickActionConfigDialog>()
                .forEach { it.view?.findViewById<View>(R.id.iv_close)?.performClick() }
        }
        await { ReadBook.book?.bookUrl == book.bookUrl && ReadBook.curTextChapter?.isCompleted == true &&
            !it.reader.curPage.textPage.isMsgPage && it.reader.curPage.textPage.lineSize > 0 && it.bottomDialog == 0 }
    }
    private fun startSpeech() {
        scenario!!.onActivity { it.onClickReadAloud() }
        await { BaseReadAloudService.isPlay() && requests.get() > 0 }
    }
    private val ReadBookActivity.reader: ReadView get() = findViewById(R.id.read_view)
    private val ReadBookActivity.bar: View get() = findViewById(R.id.read_aloud_float_bar_container)
    private val ReadBookActivity.pause: ImageButton get() = findViewById(R.id.iv_pause_aloud)
    private fun controlsDialog(activity: ReadBookActivity): ReadAloudControlsDialog? {
        val settings = activity.supportFragmentManager.findFragmentByTag("settings-test") as? ReadAloudConfigDialog
        val fragment = settings?.childFragmentManager?.fragments?.firstOrNull()
        return fragment?.childFragmentManager?.fragments?.filterIsInstance<ReadAloudControlsDialog>()?.firstOrNull()
    }
    private fun controlsFragment(activity: ReadBookActivity) = controlsDialog(activity)
        ?.childFragmentManager?.fragments?.filterIsInstance<ReadAloudControlsDialog.ControlsPreferenceFragment>()?.firstOrNull()
    private fun drag(view: View, dx: Float, dy: Float) {
        val now = SystemClock.uptimeMillis()
        listOf(Triple(MotionEvent.ACTION_DOWN, 24f, 24f), Triple(MotionEvent.ACTION_MOVE, 24f + dx, 24f + dy),
            Triple(MotionEvent.ACTION_UP, 24f + dx, 24f + dy)).forEachIndexed { index, (action, x, y) ->
            MotionEvent.obtain(now, now + index * 100L, action, x, y, 0).also {
                view.dispatchTouchEvent(it); it.recycle()
            }
        }
    }
    private fun await(condition: (ReadBookActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        do {
            var ready = false
            scenario!!.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Reader did not reach the expected playback controls state")
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream()
                .use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
