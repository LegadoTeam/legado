package io.legado.app.ui.widget.image

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.model.BookCover
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.config.ConfigTag
import io.legado.app.ui.config.CoverFontConfigFragment
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CoverStylePreviewUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = context.defaultSharedPreferences
    private val saved = HashMap(preferences.all)
    private val savedNight = AppConfig.isNightTheme
    private val files = mutableListOf<File>()
    private val styleKeys = listOf(PreferKey.coverHorizontal, PreferKey.coverTitleAdaptive,
        PreferKey.coverKeepPunctuation, PreferKey.coverFont, PreferKey.coverCustomFontSize,
        PreferKey.coverTitleLargeSize, PreferKey.coverTitleSmallSize,
        PreferKey.coverAuthorLargeSize, PreferKey.coverAuthorSmallSize)

    @After
    fun tearDown() {
        preferences.edit().clear().apply {
            saved.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
        }.commit()
        instrumentation.runOnMainSync {
            AppConfig.isNightTheme = savedNight
            ThemeConfig.applyDayNight(context)
        }
        files.forEach(File::delete)
    }

    private fun launch(): ActivityScenario<ConfigActivity> {
        preferences.edit().putBoolean(PreferKey.coverShowName, true)
            .putBoolean(PreferKey.coverShowAuthor, true)
            .putBoolean(PreferKey.coverShowNameN, true)
            .putBoolean(PreferKey.coverShowAuthorN, true).commit()
        instrumentation.runOnMainSync { BookCover.upDefaultCover() }
        return ActivityScenario.launch(Intent(context, ConfigActivity::class.java)
            .putExtra("configTag", ConfigTag.COVER_FONT_CONFIG))
    }

    @Test
    fun orderedScrollableSettingsUpdateRealPreviewsAndSurviveRecreation() {
        preferences.edit().putBoolean(PreferKey.coverHorizontal, false)
            .putBoolean(PreferKey.coverTitleAdaptive, true)
            .putBoolean(PreferKey.coverKeepPunctuation, false)
            .putBoolean(PreferKey.coverCustomFontSize, false).putString(PreferKey.coverFont, "").commit()
        launch().use { settings ->
            settings.onActivity { activity ->
                val screen = fragment(activity).preferenceScreen
                assertEquals(styleKeys + "coverPreview", (0 until screen.preferenceCount)
                    .map { screen.getPreference(it).key })
            }
            val before = previews(settings)
            settings.onActivity { assertTrue("small screens scroll through all options",
                fragment(it).listView.canScrollVertically(-1)) }
            for ((key, label) in listOf(PreferKey.coverHorizontal to R.string.cover_horizontal,
                PreferKey.coverTitleAdaptive to R.string.cover_title_adaptive,
                PreferKey.coverKeepPunctuation to R.string.cover_keep_punctuation,
                PreferKey.coverCustomFontSize to R.string.cover_custom_font_size)) {
                scroll(settings, key)
                onView(withText(label)).perform(click())
            }
            val after = previews(settings)
            assertFalse("real cover pixels update without leaving settings", before[1].contentEquals(after[1]))
            assertTrue(BookCover.drawBookNameHorizontal)
            assertFalse(BookCover.adaptiveTitleSize)
            assertTrue(BookCover.keepPunctuation)
            assertNotNull(BookCover.fontSizes)
            screenshot("cover-style-settings-live")
            settings.recreate()
            val recreated = previews(settings)
            after.zip(recreated).forEach { (expected, actual) -> assertArrayEquals(expected, actual) }
            screenshot("cover-style-settings-recreated")
        }
    }

    @Test
    fun selectedFontIsIndependentAndDayNightPreviewsUseActualCustomBackgrounds() {
        val readerFont = ReadBookConfig.textFont
        val readerTitleFont = ReadBookConfig.titleFont
        val systemFont = AppConfig.systemTypefaces
        val sourceFont = File("/system/fonts").listFiles().orEmpty().first {
            it.extension == "ttf" && it.name.contains("Serif")
        }
        val font = File(context.externalFiles, "font/cover-preview-${UUID.randomUUID()}.ttf")
        font.parentFile!!.mkdirs()
        sourceFont.copyTo(font)
        files.add(font)
        preferences.edit().remove(PreferKey.fontFolder).putString(PreferKey.coverFont, "").commit()
        for ((night, color) in listOf(false to Color.rgb(241, 219, 184), true to Color.rgb(39, 71, 95))) {
            val image = File(context.cacheDir, "cover-preview-${UUID.randomUUID()}.png")
            Bitmap.createBitmap(90, 120, Bitmap.Config.ARGB_8888).apply {
                eraseColor(color)
                image.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
                recycle()
            }
            files.add(image)
            preferences.edit().putString(if (night) PreferKey.defaultCoverDark else PreferKey.defaultCover,
                image.path).commit()
            instrumentation.runOnMainSync {
                AppConfig.isNightTheme = night
                ThemeConfig.applyDayNight(context)
            }
            launch().use { settings ->
                val background = previews(settings)
                background.forEach { assertEquals("current custom background is rendered", color, it[0]) }
                scroll(settings, PreferKey.coverFont)
                onView(withText(R.string.cover_font_select)).perform(click())
                waitUntil {
                    var found = false
                    settings.onActivity { activity ->
                        val matches = arrayListOf<View>()
                        fragment(activity).childFragmentManager.fragments.firstOrNull()?.view
                            ?.findViewsWithText(matches, font.name, View.FIND_VIEWS_WITH_TEXT)
                        found = matches.isNotEmpty()
                    }
                    found
                }
                onView(withText(context.getString(R.string.font_item_private, font.name))).perform(click())
                waitUntil { preferences.getString(PreferKey.coverFont, "") == font.path }
                assertNotNull(BookCover.fontTypeface)
                assertEquals(readerFont, ReadBookConfig.textFont)
                assertEquals(readerTitleFont, ReadBookConfig.titleFont)
                assertEquals(systemFont, AppConfig.systemTypefaces)
                previews(settings)
                screenshot("cover-style-font-${if (night) "night" else "day"}")
                settings.recreate()
                previews(settings)
                assertEquals(font.path, preferences.getString(PreferKey.coverFont, ""))
                scroll(settings, PreferKey.coverFont)
                onView(withText(R.string.cover_font_select)).perform(click())
                onView(withId(R.id.menu_default)).perform(click())
                assertEquals("", preferences.getString(PreferKey.coverFont, ""))
                assertNull(BookCover.fontTypeface)
                assertEquals(systemFont, AppConfig.systemTypefaces)
                assertEquals(readerFont, ReadBookConfig.textFont)
                assertEquals(readerTitleFont, ReadBookConfig.titleFont)
            }
        }
    }

    private fun fragment(activity: ConfigActivity) = activity.supportFragmentManager
        .findFragmentByTag(ConfigTag.COVER_FONT_CONFIG) as CoverFontConfigFragment

    private fun scroll(settings: ActivityScenario<ConfigActivity>, key: String) {
        settings.onActivity { fragment(it).scrollToPreference(key) }
        instrumentation.waitForIdleSync()
    }

    private fun previews(settings: ActivityScenario<ConfigActivity>): List<IntArray> {
        scroll(settings, "coverPreview")
        var result: List<IntArray>? = null
        waitUntil {
            settings.onActivity { activity ->
                val ids = listOf(R.id.cover_preview_short, R.id.cover_preview_long)
                val titles = listOf("开源阅读", "开源阅读可以看小说、看漫画、听书")
                val authors = listOf("开源阅读LegadoTeam", "开源阅读")
                val views = ids.map { activity.findViewById<CoverImageView>(it) }
                if (views.any { it == null || it.width <= 0 || it.drawable == null }) return@onActivity
                val field = CoverImageView::class.java.getDeclaredField("currentNameBitmap").apply { isAccessible = true }
                val frames = views.map { view ->
                    Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
                }
                val ready = views.withIndex().all { (index, view) ->
                    val expected = coverBitmapCacheKey(normalizeCoverText(titles[index], BookCover.keepPunctuation)!!,
                        normalizeCoverText(authors[index], BookCover.keepPunctuation), view.width, view.height,
                        BookCover.drawBookNameHorizontal, BookCover.drawBookAuthor,
                        context.backgroundColor, context.accentColor, BookCover.adaptiveTitleSize,
                        BookCover.fontSizes, BookCover.fontCacheKey)
                    (field.get(view) as? Pair<*, *>)?.first == expected
                }
                if (ready) result = frames.map { bitmap ->
                    IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
                }
                frames.forEach(Bitmap::recycle)
            }
            result != null
        }
        return result!!
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(50)
        }
        error("cover style UI did not reach requested state")
    }

    private fun screenshot(name: String) {
        val file = File(context.getExternalFilesDir(null), "ui-regression/$name.png")
        file.parentFile!!.mkdirs()
        instrumentation.uiAutomation.takeScreenshot().apply {
            file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
    }
}
