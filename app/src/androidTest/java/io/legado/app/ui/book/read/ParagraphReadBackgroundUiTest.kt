package io.legado.app.ui.book.read

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.paragraph.ParagraphReadView
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.postEvent
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the real reader lifecycle and background pixels, without a speech service. */
@RunWith(AndroidJUnit4::class)
class ParagraphReadBackgroundUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = context.defaultSharedPreferences
    private val savedPreferences = listOf(PreferKey.paragraphReadMode, PreferKey.themeMode,
        PreferKey.readStyleSelect, PreferKey.shareLayout).associateWith { preferences.all[it] }
    private val savedEInkMode = AppConfig.isEInkMode
    private val savedHelp = LocalConfig.all["readMenuHelpVersion"] as? Int
    private val savedReadHelp = LocalConfig.all["readHelpVersion"] as? Int
    private val savedConfigs = ReadBookConfig.configList.toList()
    private val savedShare = ReadBookConfig.shareConfig
    private val savedShared = ReadBookConfig.shareLayout
    private val savedStyle = ReadBookConfig.readStyleSelect
    private val savedComic = ReadBookConfig.isComic
    private val configFiles = listOf(File(ReadBookConfig.configFilePath), File(ReadBookConfig.shareConfigFilePath))
        .associateWith { it.takeIf(File::exists)?.readBytes() }
    private var scenario: ActivityScenario<ReadBookActivity>? = null
    private lateinit var textFile: File
    private lateinit var book: Book
    private var imageFile: File? = null

    @Before fun setUp() {
        LocalConfig.edit().putInt("readMenuHelpVersion", 1).putInt("readHelpVersion", 1).commit()
        instrumentation.runOnMainSync {
            preferences.edit().putBoolean(PreferKey.paragraphReadMode, true)
                .putString(PreferKey.themeMode, "1").commit()
            AppConfig.isEInkMode = false
            ReadBookConfig.configList.clear()
            repeat(6) {
                ReadBookConfig.configList += ReadBookConfig.Config(bgStr = INITIAL_BACKGROUND)
            }
            ReadBookConfig.shareConfig = ReadBookConfig.Config()
            ReadBookConfig.shareLayout = false
            ReadBookConfig.isComic = false
            ReadBookConfig.readStyleSelect = 0
        }
        textFile = File.createTempFile("paragraph-background-", ".txt", context.cacheDir)
        textFile.writeText((1..80).joinToString("\n\n") { "背景颜色回归测试，第 $it 段文字。" })
        book = Book(bookUrl = textFile.absolutePath, originName = textFile.name, name = textFile.name,
            charset = "UTF-8", type = BookType.local or BookType.text, totalChapterNum = 1)
            .apply { setPageAnim(PageAnim.noAnim) }
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(BookChapter(bookUrl = book.bookUrl, url = "paragraph-background",
            title = "Paragraph background", start = 0L, end = textFile.length()))
        scenario = ActivityScenario.launch(Intent(context, ReadBookActivity::class.java)
            .putExtra("bookUrl", book.bookUrl))
        awaitReader()
    }

    @After fun tearDown() {
        scenario?.close()
        if (::book.isInitialized) {
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookDao.delete(book)
        }
        if (::textFile.isInitialized) textFile.delete()
        imageFile?.delete()
        TextFile.clear()
        instrumentation.runOnMainSync {
            ReadBookConfig.configList.clear()
            ReadBookConfig.configList.addAll(savedConfigs)
            ReadBookConfig.shareConfig = savedShare
            ReadBookConfig.shareLayout = savedShared
            ReadBookConfig.readStyleSelect = savedStyle
            ReadBookConfig.isComic = savedComic
            preferences.edit().apply {
                savedPreferences.forEach { (key, value) ->
                    when (value) {
                        null -> remove(key)
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is String -> putString(key, value)
                    }
                }
            }.commit()
            AppConfig.isEInkMode = savedEInkMode
            val size = context.resources.displayMetrics
            ReadBookConfig.upBg(size.widthPixels, size.heightPixels)
            ChapterProvider.upStyle()
            ReadBookConfig.saveNow()
        }
        LocalConfig.edit().apply {
            if (savedHelp == null) remove("readMenuHelpVersion") else putInt("readMenuHelpVersion", savedHelp)
            if (savedReadHelp == null) remove("readHelpVersion") else putInt("readHelpVersion", savedReadHelp)
        }.commit()
        configFiles.forEach { (file, bytes) ->
            if (bytes == null) file.delete() else file.writeBytes(bytes)
        }
    }

    @Test fun enabledReaderKeepsItsColorOnFirstOpenRecreationAndSizeChange() {
        onActivity { assertColor(paragraph(it).background, Color.parseColor(INITIAL_BACKGROUND)) }
        scenario!!.recreate()
        awaitReader()
        onActivity { assertColor(paragraph(it).background, Color.parseColor(INITIAL_BACKGROUND)) }

        var resizedHeight = 0
        onActivity {
            val paged = paged(it)
            // A layout refresh must synchronize the newly generated background without UP_CONFIG.
            ReadBookConfig.durConfig.setCurBg(0, UPDATED_BACKGROUND)
            resizedHeight = paged.height * 3 / 4
            paged.layoutParams = paged.layoutParams.apply { height = resizedHeight }
        }
        await("reader size change") { paged(it).height == resizedHeight && !paged(it).isLayoutRequested }
        onActivity {
            assertColor(paragraph(it).background, Color.parseColor(UPDATED_BACKGROUND))
            assertEquals(UPDATED_BACKGROUND, ReadBookConfig.durConfig.curBgStr())
            assertEquals(0, ReadBookConfig.durConfig.curBgType())
            assertEquals(PageAnim.noAnim, ReadBook.book?.getPageAnim())
        }
    }

    @Test fun configurationEventsAndModeSwitchesKeepPagedAndParagraphBackgroundsEquivalent() {
        onActivity {
            ReadBookConfig.durConfig.setCurBg(0, UPDATED_BACKGROUND)
            postEvent(EventBus.UP_CONFIG, arrayListOf(1))
        }
        instrumentation.waitForIdleSync()
        onActivity { assertColor(paragraph(it).background, Color.parseColor(UPDATED_BACKGROUND)) }

        val image = File.createTempFile("paragraph-background-", ".png", context.cacheDir).also { imageFile = it }
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixels(IntArray(16 * 16) { if (it % 16 < 8) Color.YELLOW else Color.BLUE },
                0, 16, 0, 0, 16, 16)
            image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        onActivity {
            ReadBookConfig.durConfig.setCurBg(2, image.absolutePath)
            ReadBookConfig.bgAlpha = 50
            postEvent(EventBus.UP_CONFIG, arrayListOf(1, 3))
        }
        instrumentation.waitForIdleSync()
        onActivity {
            val paragraphPixels = pixels(paragraph(it).background)
            assertArrayEquals(pixels(pageBackground(it)), paragraphPixels)
            assertNotEquals("The fixture must retain its two-color image", paragraphPixels[0], paragraphPixels[1])
            assertEquals(50, ReadBookConfig.bgAlpha)
        }

        onActivity {
            ReadBookConfig.bgAlpha = 0
            postEvent(EventBus.UP_CONFIG, arrayListOf(3))
        }
        instrumentation.waitForIdleSync()
        onActivity {
            // At zero image opacity both modes must still paint the same opaque mean-color base.
            assertColor(paragraph(it).background, ReadBookConfig.bgMeanColor)
            assertArrayEquals(pixels(pageBackground(it)), pixels(paragraph(it).background))
            preferences.edit().putBoolean(PreferKey.paragraphReadMode, false).commit()
            it.updateParagraphMode()
            assertEquals(View.VISIBLE, paged(it).visibility)
            assertEquals(View.GONE, paragraph(it).visibility)
            assertColor(pageBackground(it), ReadBookConfig.bgMeanColor)
            preferences.edit().putBoolean(PreferKey.paragraphReadMode, true).commit()
            it.updateParagraphMode()
            assertEquals(View.VISIBLE, paragraph(it).visibility)
            assertArrayEquals(pixels(pageBackground(it)), pixels(paragraph(it).background))
            assertEquals(image.absolutePath, ReadBookConfig.durConfig.curBgStr())
            assertEquals(2, ReadBookConfig.durConfig.curBgType())
            assertEquals(0, ReadBookConfig.bgAlpha)
            assertEquals(PageAnim.noAnim, ReadBook.book?.getPageAnim())
        }
    }

    @Test fun paddedBackgroundPreservesWindowInsetsAndDoesNotMutateTheSharedDrawable() = onActivity {
        val reader = paragraph(it)
        val savedBackground = ReadBookConfig.bg
        val savedAlpha = ReadBookConfig.bgAlpha
        val savedPadding = Rect(reader.paddingLeft, reader.paddingTop, reader.paddingRight, reader.paddingBottom)
        val padded = InsetDrawable(ColorDrawable(Color.RED), 17).apply { alpha = 211 }
        try {
            assertTrue("The fixture must supply background padding", padded.getPadding(Rect()))
            ReadBookConfig.bg = padded
            ReadBookConfig.bgAlpha = 25
            reader.setPadding(3, 7, 11, 19)
            reader.upBackground()
            assertEquals(3, reader.paddingLeft)
            assertEquals(7, reader.paddingTop)
            assertEquals(11, reader.paddingRight)
            assertEquals(19, reader.paddingBottom)
            assertEquals("The paragraph background must own its opacity", 211, padded.alpha)
        } finally {
            ReadBookConfig.bg = savedBackground
            ReadBookConfig.bgAlpha = savedAlpha
            reader.upBackground()
            reader.setPadding(savedPadding.left, savedPadding.top, savedPadding.right, savedPadding.bottom)
        }
    }

    private fun paragraph(activity: ReadBookActivity) = activity.findViewById<ParagraphReadView>(R.id.paragraph_read_view)
    private fun paged(activity: ReadBookActivity) = activity.findViewById<ReadView>(R.id.read_view)
    private fun pageBackground(activity: ReadBookActivity) =
        paged(activity).curPage.findViewById<View>(R.id.vw_root).background

    private fun assertColor(background: Drawable, color: Int) {
        pixels(background).forEach { assertEquals("Actual background pixel", color, it) }
    }

    private fun pixels(background: Drawable): IntArray {
        val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888)
        val oldBounds = Rect(background.bounds)
        return try {
            background.setBounds(0, 0, bitmap.width, bitmap.height)
            background.draw(Canvas(bitmap))
            intArrayOf(bitmap.getPixel(10, 60), bitmap.getPixel(70, 60), bitmap.getPixel(40, 10))
        } finally {
            background.bounds = oldBounds
            bitmap.recycle()
        }
    }

    private fun onActivity(action: (ReadBookActivity) -> Unit) {
        scenario!!.onActivity(action)
    }

    private fun awaitReader() = await("paragraph reader content") {
        val chapter = ReadBook.curTextChapter
        ReadBook.book?.bookUrl == book.bookUrl && chapter != null && chapter.chapter.bookUrl == book.bookUrl &&
            chapter.isCompleted && paragraph(it).isShowing(chapter) &&
            paged(it).width > 0 && paged(it).height > 0 && paragraph(it).height > 0 && it.bottomDialog == 0
    }

    private fun await(description: String, condition: (ReadBookActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private companion object {
        const val INITIAL_BACKGROUND = "#D3E4C5"
        const val UPDATED_BACKGROUND = "#A6B8D2"
    }
}
