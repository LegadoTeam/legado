package io.legado.app.ui.widget.image

import android.content.Intent
import android.app.Activity
import android.app.Instrumentation
import android.os.SystemClock
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import android.widget.FrameLayout
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.model.BookCover
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.config.ConfigTag
import io.legado.app.ui.config.CoverConfigFragment
import io.legado.app.ui.file.HandleFileActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.R
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoverTitleAdaptiveUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = context.defaultSharedPreferences
    private val saved = HashMap(preferences.all)
    private var oldUseDefault = AppConfig.useDefaultCover
    private var scenario: ActivityScenario<AboutActivity>? = null
    private var cover: CoverImageView? = null

    @Before
    fun setUp() {
        preferences.edit()
            .putBoolean(PreferKey.useDefaultCover, false)
            .putBoolean(PreferKey.coverShowName, true)
            .putBoolean(PreferKey.coverShowAuthor, false)
            .putBoolean(PreferKey.coverHorizontal, false)
            .putBoolean(PreferKey.coverTitleAdaptive, true)
            .putBoolean(PreferKey.coverKeepPunctuation, true)
            .commit()
        AppConfig.useDefaultCover = false
        BookCover.upDefaultCover()
        scenario = ActivityScenario.launch(AboutActivity::class.java)
        scenario!!.onActivity { activity ->
            val root = FrameLayout(activity)
            val view = CoverImageView(activity)
            root.addView(view, FrameLayout.LayoutParams(240, 320))
            activity.setContentView(root)
            cover = view
            view.load(path = null, name = "适配封面标题ABCDEFG", author = null)
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        preferences.edit().clear().apply { saved.forEach { (key, value) -> putValue(key, value) } }.commit()
        AppConfig.useDefaultCover = oldUseDefault
        BookCover.upDefaultCover()
    }

    @Test
    fun adaptiveToggleChangesTheActualRenderedCover() {
        for (horizontal in listOf(false, true)) {
            preferences.edit().putBoolean(PreferKey.coverHorizontal, horizontal)
                .putBoolean(PreferKey.coverTitleAdaptive, true).commit()
            instrumentation.runOnMainSync { BookCover.upDefaultCover(); cover!!.invalidate() }
            val adaptive = renderedCover()
            screenshot("cover-title-${if (horizontal) "horizontal" else "vertical"}-on")
            preferences.edit().putBoolean(PreferKey.coverTitleAdaptive, false).commit()
            instrumentation.runOnMainSync { BookCover.upDefaultCover(); cover!!.invalidate() }
            val fixed = renderedCover()
            screenshot("cover-title-${if (horizontal) "horizontal" else "vertical"}-off")
            assertFalse("adaptive setting must change rendered title pixels, horizontal=$horizontal",
                adaptive.contentEquals(fixed))
            assertTrue("both renders contain visible cover pixels", adaptive.any { it != 0 } && fixed.any { it != 0 })
        }
    }

    @Test
    fun recordCoverPreferencesCopyBothSelectedImagesThroughTheActivityResult() {
        scenario?.close()
        scenario = null
        preferences.edit().remove(PreferKey.readRecordCover).remove(PreferKey.readRecordCoverDark).commit()
        val image = File.createTempFile("record-cover-selection", ".png", context.cacheDir)
        Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(49, 123, 191))
            image.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val existing = File(context.externalFiles, "covers").listFiles()?.map { it.name }.orEmpty().toSet()
        val copied = mutableSetOf<File>()
        var selections = 0
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className != HandleFileActivity::class.java.name) return null
                assertEquals(HandleFileContract.IMAGE, intent.getIntExtra("mode", -1))
                selections++
                val result = Intent().setData(FileProvider.getUriForFile(
                    context, "${context.packageName}.fileProvider", image))
                    .putExtra("value", intent.getStringExtra("value"))
                // A recreated contract has no transient requestCode; the persisted value still routes the result.
                val restoredResult = HandleFileContract().parseResult(Activity.RESULT_OK, result)
                assertEquals(0, restoredResult.requestCode)
                assertEquals(result.data, restoredResult.uri)
                assertEquals(intent.getStringExtra("value"), restoredResult.value)
                assertTrue(restoredResult.value in listOf(PreferKey.readRecordCover, PreferKey.readRecordCoverDark))
                return Instrumentation.ActivityResult(Activity.RESULT_OK, result)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch<ConfigActivity>(Intent(context, ConfigActivity::class.java)
                .putExtra("configTag", ConfigTag.COVER_CONFIG)).use { settings ->
                for ((key, label) in listOf(PreferKey.readRecordCover to R.string.read_record_cover_day,
                    PreferKey.readRecordCoverDark to R.string.read_record_cover_night)) {
                    settings.onActivity {
                        (it.supportFragmentManager.findFragmentByTag(ConfigTag.COVER_CONFIG) as CoverConfigFragment)
                            .scrollToPreference(key)
                    }
                    onView(withText(label)).perform(click())
                    val deadline = SystemClock.uptimeMillis() + 5000
                    while (preferences.getString(key, null) == null && SystemClock.uptimeMillis() < deadline) {
                        instrumentation.waitForIdleSync()
                        SystemClock.sleep(50)
                    }
                    val path = checkNotNull(preferences.getString(key, null))
                    val file = File(path)
                    if (file.name !in existing) copied.add(file)
                    assertEquals(File(context.externalFiles, "covers"), file.parentFile)
                    assertArrayEquals(image.readBytes(), file.readBytes())
                }
                assertEquals(2, selections)
                screenshot("reading-history-cover-settings")
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            copied.forEach { it.delete() }
            image.delete()
        }
    }

    @Test
    fun coverSettingsToggleUpdatesRuntimeImmediately() {
        scenario?.close()
        scenario = null
        ActivityScenario.launch<ConfigActivity>(Intent(context, ConfigActivity::class.java)
            .putExtra("configTag", ConfigTag.COVER_CONFIG)).use {
            onView(withText(R.string.cover_title_adaptive)).perform(click())
            assertFalse(preferences.getBoolean(PreferKey.coverTitleAdaptive, true))
            assertFalse(BookCover.adaptiveTitleSize)
            screenshot("cover-title-setting-off")
            onView(withText(R.string.cover_title_adaptive)).perform(click())
            assertTrue(preferences.getBoolean(PreferKey.coverTitleAdaptive, false))
            assertTrue(BookCover.adaptiveTitleSize)
            screenshot("cover-title-setting-on")
        }
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

    private fun renderedCover(): IntArray {
        Thread.sleep(1500)
        repeat(40) {
            var pixels: IntArray? = null
            instrumentation.runOnMainSync {
                val view = cover!!
                if (view.width <= 0 || view.height <= 0) return@runOnMainSync
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                pixels = bitmap.getPixels()
                bitmap.recycle()
            }
            if (pixels?.any { it != 0 } == true) return pixels!!
            Thread.sleep(100)
        }
        error("cover did not render")
    }

    private fun Bitmap.getPixels(): IntArray {
        val values = IntArray(width * height)
        getPixels(values, 0, width, 0, 0, width, height)
        return values
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
    }
}
