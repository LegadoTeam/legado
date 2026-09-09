package io.legado.app.ui.widget.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.model.BookCover
import io.legado.app.ui.about.AboutActivity
import io.legado.app.utils.defaultSharedPreferences
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        val adaptive = renderedCover()
        preferences.edit().putBoolean(PreferKey.coverTitleAdaptive, false).commit()
        instrumentation.runOnMainSync {
            BookCover.upDefaultCover()
            cover!!.invalidate()
        }
        val fixed = renderedCover()
        assertFalse("adaptive setting must change rendered title pixels", adaptive.contentEquals(fixed))
        assertTrue("both renders contain visible cover pixels", adaptive.any { it != 0 } && fixed.any { it != 0 })
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
