package io.legado.app.ui.book.read

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PageAnim
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.parseReadConfigObject
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.read.config.ReadStyleDialog
import io.legado.app.ui.book.read.config.TipConfigDialog
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.GSON
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class TitleFontWeightRenderingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val savedConfigs = ReadBookConfig.configList.map { it.copy() }
    private val savedShare = ReadBookConfig.shareConfig.copy()
    private val savedShareLayout = ReadBookConfig.shareLayout
    private val savedStyle = ReadBookConfig.readStyleSelect
    private val savedComic = ReadBookConfig.isComic
    private val savedSystemTypeface = AppConfig.systemTypefaces
    private val configFiles = listOf(File(ReadBookConfig.configFilePath), File(ReadBookConfig.shareConfigFilePath))
        .associateWith { it.takeIf(File::exists)?.readBytes() }
    private var scenario: ActivityScenario<ReadBookActivity>? = null
    private var book: Book? = null
    private var textFile: File? = null

    @Before
    fun setUp() {
        instrumentation.runOnMainSync {
            ReadBookConfig.isComic = false
            ReadBookConfig.readStyleSelect = 0
            ReadBookConfig.shareLayout = false
            ReadBookConfig.configList.clear()
            repeat(6) { ReadBookConfig.configList += ReadBookConfig.Config() }
            ReadBookConfig.shareConfig = ReadBookConfig.Config()
            AppConfig.systemTypefaces = 0
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        book?.let { appDb.bookDao.delete(it) }
        textFile?.delete()
        TextFile.clear()
        instrumentation.runOnMainSync {
            ReadBookConfig.configList.clear()
            ReadBookConfig.configList.addAll(savedConfigs)
            ReadBookConfig.shareConfig = savedShare
            ReadBookConfig.shareLayout = savedShareLayout
            ReadBookConfig.readStyleSelect = savedStyle
            ReadBookConfig.isComic = savedComic
            AppConfig.systemTypefaces = savedSystemTypeface
            ChapterProvider.upStyle()
        }
        configFiles.forEach { (file, bytes) ->
            if (bytes == null) file.delete() else file.writeBytes(bytes)
        }
    }

    @Test
    fun legacyAndIndependentWeightsReachTheActualPaintAndCanvas() {
        instrumentation.runOnMainSync {
            for (bodyWeight in 0..2) {
                ReadBookConfig.durConfig = parseReadConfigObject("""{"textBold":$bodyWeight}""").getOrThrow()
                ChapterProvider.upStyle()
                assertFontWeight(ChapterProvider.titlePaint.typeface, listOf(700, 900, 400)[bodyWeight])
                assertFontWeight(ChapterProvider.contentPaint.typeface, listOf(400, 700, 300)[bodyWeight])
            }
            ReadBookConfig.textBold = 1
            ChapterProvider.upStyle()
            val bodyPixels = renderedPixels(ChapterProvider.contentPaint)
            val titlePixels = mutableListOf<IntArray>()
            for ((setting, weight) in listOf(0 to 400, 1 to 700, 2 to 300)) {
                ReadBookConfig.titleBold = setting
                ChapterProvider.upStyle()
                assertFontWeight(ChapterProvider.titlePaint.typeface, weight)
                assertFontWeight(ChapterProvider.titleNumberPaint.typeface, weight)
                assertTrue("Changing title weight must not redraw body glyphs differently",
                    bodyPixels.contentEquals(renderedPixels(ChapterProvider.contentPaint)))
                titlePixels += renderedPixels(ChapterProvider.titlePaint)
            }
            assertFalse("Normal and bold title glyphs must differ", titlePixels[0].contentEquals(titlePixels[1]))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                assertFalse("Normal and light system title glyphs must differ", titlePixels[0].contentEquals(titlePixels[2]))
            }
            // A separate installed title font follows the same independent weight selection.
            val serif = File("/system/fonts/NotoSerif-Regular.ttf")
            assertTrue("The emulator must provide its regular Noto Serif font fixture", serif.isFile)
            ReadBookConfig.titleFont = serif.absolutePath
            ReadBookConfig.titleBold = 0
            ChapterProvider.upStyle()
            assertEquals(serif.absolutePath, ReadBookConfig.titleFont)
            assertFontWeight(ChapterProvider.titlePaint.typeface, 400)
            assertTrue(bodyPixels.contentEquals(renderedPixels(ChapterProvider.contentPaint)))
            ReadBookConfig.textBold = 2
            ChapterProvider.upStyle()
            assertFontWeight(ChapterProvider.titlePaint.typeface, 400)
            assertFontWeight(ChapterProvider.contentPaint.typeface, 300)
        }
    }

    @Test
    fun savedStylesSharedLayoutAndZipImportKeepSeparateWeights() {
        ReadBookConfig.configList[0].apply { titleBold = 0; textBold = 1 }
        ReadBookConfig.configList[1].apply { titleBold = 2; textBold = 0 }
        ReadBookConfig.shareConfig.apply { titleBold = 1; textBold = 2 }
        ReadBookConfig.saveNow()
        ReadBookConfig.configList[0].titleBold = -1
        ReadBookConfig.shareConfig.titleBold = -1
        ReadBookConfig.initConfigs()
        ReadBookConfig.initShareConfig()
        assertEquals(0, ReadBookConfig.titleBold)
        assertEquals(1, ReadBookConfig.textBold)
        ReadBookConfig.readStyleSelect = 1
        assertEquals(2, ReadBookConfig.titleBold)
        ReadBookConfig.shareLayout = true
        val sharedExport = ReadBookConfig.getExportConfig()
        assertEquals(1, sharedExport.titleBold)
        assertEquals(2, sharedExport.textBold)
        val bytes = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip ->
                zip.putNextEntry(ZipEntry(ReadBookConfig.configFileName))
                zip.write(GSON.toJson(sharedExport).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }.toByteArray()
        val imported = ReadBookConfig.import(bytes)
        assertEquals(1, imported.titleBold)
        assertEquals(2, imported.textBold)
        ReadBookConfig.durConfig = imported
        ReadBookConfig.saveNow()
        ReadBookConfig.initConfigs()
        ReadBookConfig.initShareConfig()
        assertEquals(1, ReadBookConfig.titleBold)
        ReadBookConfig.shareLayout = false
        assertEquals(1, ReadBookConfig.titleBold)
        ReadBookConfig.readStyleSelect = 0
        assertEquals(0, ReadBookConfig.titleBold)
    }

    @Test
    fun informationPanelChangesOnlyTitleAndRestoresTheSelectedValue() {
        ReadBookConfig.textBold = 1
        launchReader()
        scenario!!.onActivity {
            ReadStyleDialog().showNow(it.supportFragmentManager, "title-weight-style")
        }
        onView(withId(R.id.tv_tip)).perform(scrollTo()).check(matches(isDisplayed()))
        screenshot("title-weight-information-entry")
        onView(withId(R.id.tv_tip)).perform(click())
        onView(withId(R.id.ll_title_font_weight)).check(matches(isDisplayed()))
        val weights = context.resources.getStringArray(R.array.text_font_weight)
        chooseTitleWeight(weights[0], 0, 400)
        screenshot("title-weight-settings-normal")
        chooseTitleWeight(weights[1], 1, 700)
        chooseTitleWeight(weights[2], 2, 300)
        chooseTitleWeight(context.getString(R.string.title_font_weight_follow), -1, 900)
        chooseTitleWeight(weights[0], 0, 400)
        dismissSettings()
        awaitReader { ReadBook.curTextChapter?.isCompleted == true && it.bottomDialog == 0 }
        screenshot("title-weight-reader-normal-body-bold")
        ReadBookConfig.saveNow()
        scenario!!.recreate()
        awaitReader { !it.findViewById<ReadView>(R.id.read_view).curPage.textPage.isMsgPage }
        scenario!!.onActivity {
            assertEquals(0, ReadBookConfig.titleBold)
            assertFontWeight(ChapterProvider.titlePaint.typeface, 400)
            assertFontWeight(ChapterProvider.contentPaint.typeface, 700)
            ReadStyleDialog().showNow(it.supportFragmentManager, "title-weight-style")
        }
        onView(withId(R.id.tv_tip)).perform(scrollTo(), click())
        onView(withId(R.id.tv_title_font_weight)).check(matches(withText(weights[0])))
        screenshot("title-weight-settings-restored")
        dismissSettings()
    }

    private fun chooseTitleWeight(label: String, setting: Int, weight: Int) {
        onView(withId(R.id.ll_title_font_weight)).perform(click())
        onView(withText(label)).inRoot(isDialog()).perform(click())
        awaitReader { ReadBookConfig.titleBold == setting && ChapterProvider.titlePaint.typeface.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.weight == weight
            else it.isBold == (weight >= 700)
        } }
        assertEquals(1, ReadBookConfig.textBold)
        instrumentation.runOnMainSync { assertFontWeight(ChapterProvider.contentPaint.typeface, 700) }
    }

    private fun launchReader() {
        val file = File.createTempFile("title-weight-", ".txt", context.cacheDir).also { textFile = it }
        file.writeText((0 until 60).joinToString("\n") {
            "Line $it: the body remains bold while the heading uses its own font weight."
        })
        val fixture = Book(bookUrl = file.absolutePath, originName = file.name, name = file.name,
            charset = "UTF-8", type = BookType.local or BookType.text, totalChapterNum = 1)
            .apply { setPageAnim(PageAnim.noAnim) }
        book = fixture
        appDb.bookDao.insert(fixture)
        appDb.bookChapterDao.insert(BookChapter(bookUrl = fixture.bookUrl, url = "title-weight-chapter",
            title = "Chapter 1 Independent Title", start = 0L, end = file.length()))
        scenario = ActivityScenario.launch(Intent(context, ReadBookActivity::class.java)
            .putExtra("bookUrl", fixture.bookUrl))
        scenario!!.onActivity { activity ->
            activity.supportFragmentManager.fragments.filterIsInstance<ClickActionConfigDialog>()
                .forEach { it.view?.findViewById<View>(R.id.iv_close)?.performClick() }
        }
        awaitReader {
            val page = it.findViewById<ReadView>(R.id.read_view).curPage.textPage
            ReadBook.book?.bookUrl == fixture.bookUrl && ReadBook.curTextChapter?.isCompleted == true &&
                !page.isMsgPage && page.lines.any { it.isTitle } && it.bottomDialog == 0
        }
    }

    private fun dismissSettings() {
        scenario!!.onActivity { activity ->
            val style = activity.supportFragmentManager.findFragmentByTag("title-weight-style") as ReadStyleDialog
            (style.childFragmentManager.findFragmentByTag("tipConfigDialog") as TipConfigDialog).dismissNow()
            style.dismissNow()
        }
    }

    private fun assertFontWeight(typeface: Typeface, weight: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) assertEquals(weight, typeface.weight)
        else assertEquals(weight >= 700, typeface.isBold)
    }

    private fun renderedPixels(paint: Paint): IntArray {
        val bitmap = Bitmap.createBitmap(640, 100, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawText("Heading WMWM abcdef", 8f, 75f, Paint(paint).apply {
                color = Color.BLACK
                textSize = 48f
            })
            return IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue("The real paint must render nonempty glyphs", it.any { pixel -> Color.alpha(pixel) > 0 })
            }
        } finally { bitmap.recycle() }
    }

    private fun awaitReader(condition: (ReadBookActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        do {
            var ready = false
            scenario!!.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Reader did not reach the expected title font weight state")
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
