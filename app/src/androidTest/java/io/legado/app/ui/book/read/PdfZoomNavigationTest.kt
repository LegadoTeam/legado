package io.legado.app.ui.book.read

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.PdfFile
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.PdfZoomRenderer
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.utils.defaultSharedPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfZoomNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = context.defaultSharedPreferences
    private val savedDoublePage = preferences.getString(PreferKey.doublePageHorizontal, null)
    private val savedActions = listOf(AppConfig.clickActionBR, AppConfig.clickActionBL, AppConfig.clickActionMC)
    private val savedOptimize = AppConfig.optimizeRender
    private lateinit var book: Book
    private lateinit var file: File
    private var scenario: ActivityScenario<ReadBookActivity>? = null
    private val ReadBookActivity.reader: ReadView get() = findViewById(R.id.read_view)
    private val ReadView.content: ContentTextView get() = curPage.findViewById(R.id.content_text_view)

    @Before
    fun setUp() {
        file = File.createTempFile("pdf-zoom-", ".pdf", context.cacheDir)
        val document = PdfDocument()
        try {
            repeat(14) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 900, index + 1).create())
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.color = if (index % 2 == 0) Color.rgb(20, 100, 170) else Color.rgb(170, 50, 30)
                canvas.drawRect(24f, 24f, 576f, 100f, paint)
                paint.color = Color.WHITE; paint.textSize = 32f
                canvas.drawText("MATHEMATICS / PAGE ${index + 1}", 35f, 70f, paint)
                paint.color = Color.BLACK; paint.textSize = 10f
                repeat(30) { row ->
                    canvas.drawText("${row + 1}. Measure the diagram. 12 x 8 = 96. Fine vector text ABC 123.",
                        35f, 145f + row * 21f, paint)
                }
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 0.3f
                repeat(81) { n ->
                    val p = 180f + n * 3f
                    canvas.drawLine(p, 260f, p, 500f, paint)
                    canvas.drawLine(180f, p + 80f, 420f, p + 80f, paint)
                }
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        book = Book(bookUrl = file.absolutePath, originName = file.name, name = file.name,
            type = BookType.local or BookType.text, totalChapterNum = 2)
        book.setImageStyle(Book.imgStyleSingle)
        book.setPageAnim(PageAnim.noAnim)
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(*PdfFile.getChapterList(book).toTypedArray())
        instrumentation.runOnMainSync {
            AppConfig.optimizeRender = false
            AppConfig.clickActionBR = 1
            AppConfig.clickActionBL = 2
            AppConfig.clickActionMC = 0
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        PdfFile.clear(book.bookUrl)
        appDb.bookDao.delete(book)
        file.delete()
        preferences.edit().putString(PreferKey.doublePageHorizontal, savedDoublePage).commit()
        instrumentation.runOnMainSync {
            AppConfig.clickActionBR = savedActions[0]
            AppConfig.clickActionBL = savedActions[1]
            AppConfig.clickActionMC = savedActions[2]
            AppConfig.optimizeRender = savedOptimize
        }
    }

    @Test
    fun portraitPinchPanConfiguredTapsAndChapterTransitionKeepScale() {
        launch()
        val initial = images()
        screenshot("pdf-zoom-portrait-fit")
        var preview: Bitmap? = null
        scenario!!.onActivity {
            pinch(it.reader, true)
            preview = captureContent(it.reader)
        }
        await { it.reader.pdfZoom.scale > 2f && it.reader.content.pdfRenderCount > 0 }
        scenario!!.onActivity {
            val detail = captureContent(it.reader)
            assertFalse("Native PDF rendering must replace the enlarged screen-resolution preview",
                preview!!.sameAs(detail))
            saveBitmap(preview!!, "pdf-zoom-scaled-preview")
            saveBitmap(detail, "pdf-zoom-native-detail")
            preview!!.recycle()
            detail.recycle()
        }
        val scale = zoomScale()
        assertEquals("A two-finger gesture must not navigate", initial, images())
        screenshot("pdf-zoom-portrait-detail")
        var x = 0f
        scenario!!.onActivity { activity ->
            x = activity.reader.pdfZoom.offsetX
            drag(activity.reader, 90f, 60f)
            assertTrue(activity.reader.pdfZoom.offsetX > x)
        }
        assertEquals(initial, images())
        await { it.reader.content.pdfRenderCount >= 2 }
        scenario!!.onActivity { tap(it.reader, .5f, .5f) }
        await { it.findViewById<View>(R.id.read_menu).visibility == View.VISIBLE }
        screenshot("pdf-zoom-configured-menu")
        scenario!!.onActivity { it.findViewById<ReadMenu>(R.id.read_menu).runMenuOut(false) }
        // Swap the lower-right action to previous: the zoom path must use the configured value.
        scenario!!.onActivity { AppConfig.clickActionBR = 2; tap(it.reader, .85f, .85f) }
        assertEquals(initial, images())
        scenario!!.onActivity { AppConfig.clickActionBR = 1 }
        repeat(11) {
            val previous = images()
            scenario!!.onActivity { tap(it.reader, .85f, .85f) }
            await { images(it) != previous }
            assertEquals(scale, zoomScale(), .001f)
        }
        assertTrue("Cross the ten-page PDF chapter boundary", ReadBook.durChapterIndex > 0)
        await { it.reader.content.pdfRenderCount > 0 && it.reader.content.pdfRenderedPixelCount > 0 }
        screenshot("pdf-zoom-page-12")
        scenario!!.recreate()
        await { it.reader.pdfZoom.scale == scale && it.reader.content.pdfRenderCount > 0 }
        assertTrue(images().contains(11))
    }

    @Test
    fun landscapeSpreadPansAcrossBothPagesAndKeepsZoomWhenRotated() {
        instrumentation.runOnMainSync { AppConfig.optimizeRender = true }
        launch(landscape = true)
        await { images(it).size == 2 }
        val initial = images()
        screenshot("pdf-zoom-landscape-spread")
        scenario!!.onActivity { pinch(it.reader, true) }
        await { it.reader.content.pdfRenderCount > 0 }
        val scale = zoomScale()
        assertTrue(scale > 2f)
        scenario!!.onActivity { drag(it.reader, -300f, 40f) }
        await { it.reader.content.pdfRenderCount >= 2 }
        screenshot("pdf-zoom-landscape-right-detail")
        assertEquals(initial, images())
        scenario!!.onActivity { tap(it.reader, .85f, .85f) }
        await { images(it).firstOrNull() == initial.first() + 2 }
        assertEquals(scale, zoomScale(), .001f)
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        await { it.reader.width < it.reader.height && images(it).size == 1 }
        assertEquals(scale, zoomScale(), .001f)
        await { it.reader.content.pdfRenderCount > 0 }
        screenshot("pdf-zoom-rotation-keeps-scale")
    }

    @Test
    fun reportersTextbookRendersSharpSingleAndDoublePageRegions() {
        PdfFile.clear(book.bookUrl)
        instrumentation.context.assets.open("pdf_zoom_textbook.pdf").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        val chapters = PdfFile.getChapterList(book)
        book.totalChapterNum = chapters.size
        book.durChapterPos = 5
        appDb.bookDao.update(book)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        launch()
        await { images(it).contains(5) }
        screenshot("pdf-zoom-reporter-textbook-fit")
        scenario!!.onActivity { pinch(it.reader, true) }
        await { it.reader.content.pdfRenderCount > 0 }
        screenshot("pdf-zoom-reporter-textbook-detail")
        val scale = zoomScale()
        val previous = images()
        scenario!!.onActivity { tap(it.reader, .85f, .85f) }
        await { images(it) != previous && it.reader.content.pdfRenderCount > 0 }
        assertEquals(scale, zoomScale(), .001f)
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        await { it.reader.width > it.reader.height && images(it).size == 2 }
        scenario!!.onActivity { drag(it.reader, -250f, 0f) }
        await { it.reader.content.pdfRenderCount > 0 }
        screenshot("pdf-zoom-reporter-textbook-spread-detail")
    }

    @Test
    fun zoomedScrollingPansWithoutChangingReadingPositionAndPinchBackRestoresSwipe() {
        book.setPageAnim(PageAnim.scrollPageAnim)
        book.setImageStyle(Book.imgStyleFull)
        appDb.bookDao.update(book)
        launch()
        scenario!!.onActivity { pinch(it.reader, true) }
        await { it.reader.content.pdfRenderCount > 0 }
        val initial = ReadBook.durChapterPos
        scenario!!.onActivity { drag(it.reader, -100f, -150f) }
        assertEquals(initial, ReadBook.durChapterPos)
        await { it.reader.content.pdfRenderCount >= 2 }
        screenshot("pdf-zoom-scroll-detail")
        scenario!!.onActivity { pinch(it.reader, false) }
        assertEquals(1f, zoomScale(), .001f)
        val offset = ContentTextView::class.java.getDeclaredField("pageOffset").apply { isAccessible = true }
        scenario!!.onActivity { activity ->
            val before = offset.getInt(activity.reader.content)
            drag(activity.reader, 0f, -150f)
            assertTrue("Ordinary scroll resumes at 1x", offset.getInt(activity.reader.content) < before)
        }
    }

    private fun launch(landscape: Boolean = false) {
        assertTrue(preferences.edit().putString(PreferKey.doublePageHorizontal, "2").commit())
        scenario = ActivityScenario.launch(Intent(context, ReadBookActivity::class.java).putExtra("bookUrl", book.bookUrl))
        scenario!!.onActivity { activity ->
            activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            activity.supportFragmentManager.fragments.filterIsInstance<ClickActionConfigDialog>()
                .forEach { it.view?.findViewById<View>(R.id.iv_close)?.performClick() }
        }
        await { ReadBook.book?.bookUrl == book.bookUrl && images(it).isNotEmpty() &&
            ReadBook.curTextChapter?.isCompleted == true && it.bottomDialog == 0 &&
            (it.reader.width > it.reader.height) == landscape }
    }

    private fun images(activity: ReadBookActivity): List<Int> = activity.reader.curPage.textPage.lines
        .flatMap { it.columns }.filterIsInstance<ImageColumn>().mapNotNull { it.src.toIntOrNull() }
    private fun images(): List<Int> {
        var result = emptyList<Int>()
        scenario!!.onActivity { result = images(it) }
        return result
    }
    private fun zoomScale(): Float {
        var result = 0f
        scenario!!.onActivity { result = it.reader.pdfZoom.scale }
        return result
    }
    private fun await(condition: (ReadBookActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        do {
            var ready = false
            scenario!!.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        scenario!!.onActivity { assertTrue("PDF reader did not reach expected state", condition(it)) }
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        saveBitmap(screenshot, name)
        screenshot.recycle()
        scenario!!.onActivity {
            assertTrue(it.reader.content.pdfRenderedPixelCount <= PdfZoomRenderer.MAX_PIXELS)
        }
    }
    private fun captureContent(view: ReadView): Bitmap = Bitmap.createBitmap(view.content.width,
        view.content.height, Bitmap.Config.ARGB_8888).also { view.content.draw(Canvas(it)) }
    private fun saveBitmap(bitmap: Bitmap, name: String) {
        val output = File(context.getExternalFilesDir(null), "ui-regression/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun dispatch(view: ReadView, down: Long, time: Long, action: Int, points: List<Pair<Float, Float>>) {
        val event = MotionEvent.obtain(down, time, action, points.size,
            points.indices.map { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray(),
            points.map { p -> MotionEvent.PointerCoords().apply { x = p.first; y = p.second; pressure = 1f; size = 1f } }.toTypedArray(),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
    }
    private fun pinch(view: ReadView, out: Boolean) {
        val time = SystemClock.uptimeMillis()
        val center = view.width / 2f
        val y = view.height / 2f
        val start = view.width * if (out) .1f else .4f
        val end = view.width * if (out) .4f else .015f
        fun points(radius: Float) = listOf(center - radius to y, center + radius to y)
        dispatch(view, time, time, MotionEvent.ACTION_DOWN, points(start).take(1))
        dispatch(view, time, time + 20, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), points(start))
        repeat(16) { i -> dispatch(view, time, time + 40 + i * 20L, MotionEvent.ACTION_MOVE, points(start + (end - start) * (i + 1) / 16)) }
        dispatch(view, time, time + 380, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), points(end))
        dispatch(view, time, time + 400, MotionEvent.ACTION_UP, points(end).take(1))
    }
    private fun tap(view: ReadView, x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        val point = listOf(view.width * x to view.height * y)
        dispatch(view, time, time, MotionEvent.ACTION_DOWN, point)
        dispatch(view, time, time + 70, MotionEvent.ACTION_UP, point)
    }
    private fun drag(view: ReadView, dx: Float, dy: Float) {
        val time = SystemClock.uptimeMillis()
        val x = view.width / 2f
        val y = view.height / 2f
        dispatch(view, time, time, MotionEvent.ACTION_DOWN, listOf(x to y))
        repeat(8) { i -> dispatch(view, time, time + 30 + i * 20L, MotionEvent.ACTION_MOVE,
            listOf(x + dx * (i + 1) / 8 to y + dy * (i + 1) / 8)) }
        dispatch(view, time, time + 210, MotionEvent.ACTION_UP, listOf(x + dx to y + dy))
    }
}
