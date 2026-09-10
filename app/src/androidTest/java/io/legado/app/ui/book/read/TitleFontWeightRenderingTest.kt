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
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.pressBack
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.HighlightGeometry
import io.legado.app.help.HighlightMatcher
import io.legado.app.help.HighlightTextBuilder
import io.legado.app.help.book.BookContent
import io.legado.app.help.HighlightStyle
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.parseReadConfigObject
import io.legado.app.model.ReadBook
import io.legado.app.model.ImageProvider
import io.legado.app.model.localBook.TextFile
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.read.config.ReadStyleDialog
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.HighlightDraw
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.ui.book.read.page.provider.HighlightSpacing
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.LineColumnLayout
import io.legado.app.utils.GSON
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getTextWidthsCompat
import io.legado.app.utils.putPrefBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import kotlin.math.floor

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
    fun highlightPillKeepsEndGlyphsInsideItsBorderAndUsesThePageMargins() {
        launchReader()
        val savedOptimize = AppConfig.optimizeRender
        try {
            scenario!!.onActivity { activity ->
                val width = activity.findViewById<ReadView>(R.id.read_view).curPage
                    .findViewById<ContentTextView>(R.id.content_text_view).width
                for (optimized in listOf(false, true)) {
                    AppConfig.optimizeRender = optimized
                    for (size in listOf(20, 50)) {
                        for (margin in listOf(0, 24)) {
                            ReadBookConfig.textSize = size
                            ReadBookConfig.paddingLeft = margin
                            ReadBookConfig.paddingRight = margin
                            ChapterProvider.upStyle()
                            val paint = ChapterProvider.contentPaint
                            val textSize = paint.textSize
                            val lineHeight = ceil(textSize * 1.5f).toInt()
                            val top = ChapterProvider.paddingTop.toFloat()
                            val height = ceil(top + lineHeight * 2).toInt()
                            val page = TextPage(text = "多恐怖吗顶\n上", height = height.toFloat())
                            // A wrapped run at the left margin and a single glyph at the right.
                            for ((row, text) in listOf("多恐怖吗顶", "上").withIndex()) {
                                val widths = FloatArray(text.length).also {
                                    paint.getTextWidthsCompat(text, it, ChapterProvider.getReviewWidth(false))
                                }
                                var x = if (row == 0) ChapterProvider.paddingLeft.toFloat()
                                else width - ChapterProvider.paddingRight - widths.sum()
                                val y = top + row * lineHeight
                                val line = TextLine(text = text, startX = x, lineTop = y,
                                    lineBase = y + textSize, lineBottom = y + lineHeight)
                                text.forEachIndexed { index, char ->
                                    line.addColumn(TextColumn(x, x + widths[index], char.toString()))
                                    x += widths[index]
                                }
                                page.addLine(line)
                            }
                            page.upRenderHeight()
                            page.isCompleted = true
                            val view = ContentTextView(activity, null).apply {
                                layout(0, 0, width, height)
                                setContent(page)
                            }
                            val columns = page.lines.flatMap { it.columns }.filterIsInstance<TextColumn>()
                            val positions = columns.map { it.start to it.end }
                            fun render(style: HighlightStyle): Bitmap {
                                columns.forEach { it.highlightStyle = style }
                                return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                    .also { view.draw(Canvas(it)) }
                            }
                            val glyphs = render(HighlightStyle(textColor = Color.BLACK, bold = true))
                            val fill = Color.rgb(32, 144, 80)
                            // Nonzero transparent ARGB hides ink without disabling its style.
                            val background = render(HighlightStyle(fill = fill,
                                fillShape = HighlightStyle.FillShape.PILL, textColor = 1, bold = true))
                            val legacy = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            try {
                                // Save the actual production-view rendering even if an assertion fails.
                                val result = render(HighlightStyle(fill = fill,
                                    fillShape = HighlightStyle.FillShape.PILL, textColor = Color.BLACK, bold = true))
                                try {
                                    File(context.getExternalFilesDir("ui-regression"),
                                        "highlight-pill-$size-$margin-$optimized.png").outputStream().use {
                                        assertTrue(result.compress(Bitmap.CompressFormat.PNG, 100, it))
                                    }
                                } finally { result.recycle() }
                                // The unchanged low-level renderer supplies a negative control:
                                // using bare text bounds must still reproduce the reported overlap.
                                val canvas = Canvas(legacy)
                                for (line in page.lines) {
                                    val band = HighlightGeometry.fillBand(line.lineBase, textSize,
                                        line.lineBottom, HighlightStyle.FillShape.PILL, 1f.dpToPx())
                                    HighlightDraw.drawFillRun(canvas, line.lineStart, line.lineEnd,
                                        band.top, band.bottom, fill, HighlightStyle.FillShape.PILL)
                                }
                                var inkPixels = 0
                                var oldBorderCollisions = 0
                                for (y in 0 until height) for (x in 0 until width) {
                                    if (Color.alpha(glyphs.getPixel(x, y)) < 240) continue
                                    inkPixels++
                                    val alpha = Color.alpha(background.getPixel(x, y))
                                    assertTrue("Glyph crosses capsule: size=$size margin=$margin optimized=$optimized ($x,$y) alpha=$alpha",
                                        alpha in 70..110)
                                    if (Color.alpha(legacy.getPixel(x, y)) !in 70..110) oldBorderCollisions++
                                }
                                assertTrue("The fixture must draw actual Chinese glyphs", inkPixels > 50)
                                assertTrue("The original capsule must cross some glyph pixels", oldBorderCollisions > 0)
                                if (margin == 24 && textSize > 60f) {
                                    val x = floor(ChapterProvider.visibleRect.left).toInt() - 2
                                    val line = page.lines.first()
                                    val band = HighlightGeometry.fillBand(line.lineBase, textSize,
                                        line.lineBottom, HighlightStyle.FillShape.PILL, 1f.dpToPx())
                                    assertTrue("The left cap must survive the old 10px clipping limit",
                                        Color.alpha(background.getPixel(x, ((band.top + band.bottom) / 2).toInt())) > 0)
                                }
                                assertEquals("Highlights must not move text columns", positions,
                                    columns.map { it.start to it.end })
                            } finally {
                                glyphs.recycle()
                                background.recycle()
                                legacy.recycle()
                                page.recycleRecorders()
                            }
                        }
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync { AppConfig.optimizeRender = savedOptimize }
        }
    }

    @Test
    fun fillOnlyPillKeepsGlyphsInsideItsBorderWithActualWholeLineDrawing() {
        launchReader()
        val savedOptimize = AppConfig.optimizeRender
        val savedColor = ReadBookConfig.textColor
        try {
            scenario!!.onActivity { activity ->
                val width = activity.findViewById<ReadView>(R.id.read_view).curPage
                    .findViewById<ContentTextView>(R.id.content_text_view).width
                ReadBookConfig.textSize = 32
                ChapterProvider.upStyle()
                val paint = ChapterProvider.contentPaint
                val textSize = paint.textSize
                val top = ChapterProvider.paddingTop.toFloat()
                val height = ceil(top + textSize * 2).toInt()
                for (optimized in listOf(false, true)) for (scale in listOf(0.5f, 1f, 2f))
                    for (justified in listOf(false, true)) {
                    AppConfig.optimizeRender = optimized
                    val text = "顶上多恐怖吗"
                    val words = text.map(Char::toString)
                    val advances = FloatArray(text.length).also {
                        paint.getTextWidthsCompat(text, it, ChapterProvider.getReviewWidth(false))
                    }
                    val desiredWidth = advances.sum()
                    val layoutWidth = if (justified) ChapterProvider.visibleWidth.toFloat() else desiredWidth
                    val start = (width - layoutWidth) / 2f
                    val caseLabel = "optimized=$optimized scale=$scale justified=$justified"
                    val line = TextLine(text = text, startX = start, lineTop = top,
                        lineBase = top + textSize * 1.2f, lineBottom = height.toFloat())
                    if (justified) {
                        LineColumnLayout.justified(words, advances.toList(), layoutWidth, desiredWidth, start,
                            onJustify = { startX, gap, wordSpacing ->
                                line.startX = startX
                                if (wordSpacing) line.wordSpacing = gap else {
                                    line.extraLetterSpacingOffsetX = -gap / 2f
                                    line.extraLetterSpacing = gap / textSize
                                }
                            }) { index, xStart, xEnd, _ ->
                            line.addColumn(TextColumn(xStart, xEnd, words[index]))
                        }
                        assertTrue("The fixture must exercise actual justification: $caseLabel",
                            line.extraLetterSpacing > 0f)
                    } else {
                        var x = start
                        for (index in words.indices) {
                            line.addColumn(TextColumn(x, x + advances[index], words[index]))
                            x += advances[index]
                        }
                    }
                    val page = TextPage(text = text, height = height.toFloat()).apply {
                        addLine(line)
                        upRenderHeight()
                        isCompleted = true
                    }
                    val view = ContentTextView(activity, null).apply {
                        layout(0, 0, width, height)
                        setContent(page)
                    }
                    val columns = line.columns.filterIsInstance<TextColumn>()
                    val positions = columns.map { it.start to it.end }
                    fun render(color: Int, fill: Int): Bitmap {
                        ReadBookConfig.durConfig.setCurTextColor(color)
                        columns.forEach { it.highlightStyle = HighlightStyle(fill = fill,
                            fillShape = HighlightStyle.FillShape.PILL, pillPaddingScale = scale) }
                        line.invalidate()
                        assertEquals("Fill alone must preserve the actual whole-line drawing path: $caseLabel",
                            optimized, line.checkFastDraw())
                        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            .also { view.draw(Canvas(it)) }
                    }
                    val fill = Color.rgb(32, 144, 80)
                    val glyphs = render(Color.BLACK, 0)
                    val background = render(1, fill)
                    val result = render(Color.BLACK, fill)
                    try {
                        File(context.getExternalFilesDir("ui-regression"),
                            "highlight-pill-fill-only-$optimized-$scale-$justified.png").outputStream().use {
                            assertTrue(result.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                        var ink = 0
                        for (y in 0 until height) for (pixelX in 0 until width) {
                            if (Color.alpha(glyphs.getPixel(pixelX, y)) < 240) continue
                            ink++
                            assertTrue("Fast-drawn glyph crosses the capsule: $caseLabel ($pixelX,$y)",
                                Color.alpha(background.getPixel(pixelX, y)) in 70..110)
                            assertTrue("The highlight must not move the fast-drawn glyphs: $caseLabel ($pixelX,$y)",
                                Color.red(result.getPixel(pixelX, y)) < 20)
                        }
                        assertTrue("The fixture must draw actual Chinese glyphs: $caseLabel", ink > 50)
                        assertEquals("The highlight must not move text columns: $caseLabel",
                            positions, columns.map { it.start to it.end })
                    } finally {
                        listOf(glyphs, background, result).forEach(Bitmap::recycle)
                        page.recycleRecorders()
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                AppConfig.optimizeRender = savedOptimize
                ReadBookConfig.durConfig.setCurTextColor(savedColor)
            }
        }
    }

    @Test
    fun highlightPillLeavesTransparentReviewImagesClearOnBothSides() {
        launchReader()
        // The unmodified SVG supplied with https://github.com/LegadoTeam/legado/issues/1255.
        val svg = instrumentation.context.assets.open("issue1255-transparent-cat.svg")
            .bufferedReader().use { it.readText() }
        val src = "https://fixture.invalid/issue1255-transparent-cat.svg"
        val imageFile = BookHelp.getImage(book!!, src)
        imageFile.parentFile!!.mkdirs()
        imageFile.writeText(svg)
        val savedOptimize = AppConfig.optimizeRender
        val savedZhLayout = ReadBookConfig.useZhLayout
        val savedAdaptStyle = AppConfig.adaptSpecialStyle
        var hardwareView: ContentTextView? = null
        try {
            scenario!!.onActivity { activity ->
                ReadBookConfig.reviewIconSvg = svg
                val width = activity.findViewById<ReadView>(R.id.read_view).curPage
                    .findViewById<ContentTextView>(R.id.content_text_view).width
                for ((paddingScale, iconScale) in listOf(0.5f to 100, 1f to 180, 2f to 200))
                    for (optimized in listOf(false, true)) for (size in listOf(20, 50)) for (withSpace in listOf(false, true)) {
                    AppConfig.optimizeRender = optimized
                    ReadBookConfig.reviewIconScale = iconScale
                    ReadBookConfig.textSize = size
                    ChapterProvider.upStyle()
                    ImageProvider.remove(imageFile.absolutePath)
                    val caseLabel = "size=$size optimized=$optimized space=$withSpace padding=$paddingScale icon=$iconScale"
                    val textSize = ChapterProvider.contentPaint.textSize
                    val iconWidth = ceil(ChapterProvider.getReviewWidth(false))
                    val rowText = if (withSpace) " 顶上 " else "顶上"
                    val lineHeight = ceil(textSize * 2.1f)
                    assertNotNull("The author's SVG must decode through the real review icon provider: $caseLabel",
                        ChapterProvider.getReviewIconBitmap(88, iconWidth.toInt(), lineHeight.toInt()))
                    val decodedImage = ImageProvider.getImage(book!!, src, iconWidth.toInt(), lineHeight.toInt())
                    assertEquals("The image column must decode the SVG instead of using an error placeholder: $caseLabel",
                        iconWidth.toInt(), decodedImage.width)
                    assertEquals("The decoded SVG must keep its aspect ratio: $caseLabel",
                        80f / 90f, decodedImage.width.toFloat() / decodedImage.height, 0.03f)
                    ReadBookConfig.titleMode = 2
                    ReadBookConfig.paragraphIndent = ""
                    ReadBookConfig.useZhLayout = optimized
                    AppConfig.adaptSpecialStyle = true
                    val fixtureChapter = BookChapter(bookUrl = book!!.bookUrl,
                        url = "highlight-spacing-fixture", index = 10000, title = "Spacing")
                    ChapterProvider.setReviewProviders({ _, id -> if (id == 1) 88 else 0 },
                        null, fixtureChapter.index)
                    val plainImage = """<img src='$src,{"style":"text"}'>"""
                    val reviewImage = """<img src='$src,{"style":"TEXT","reviewCount":"88","click":"review"}'>"""
                    val contents = listOf(plainImage + rowText,
                        plainImage + rowText + plainImage,
                        "<usehtml><p>$reviewImage$rowText$reviewImage</p></usehtml>")
                    fun awaitLayout(chapter: TextChapter): TextChapter = runBlocking {
                        withTimeout(30_000) {
                            for (ignored in chapter.layoutChannel) Unit
                            while (!chapter.isCompleted) yield()
                        }
                        chapter
                    }
                    fun canonical(chapter: TextChapter) = HighlightTextBuilder.build(
                        chapter.pages.flatMap { it.lines }.map {
                            HighlightTextBuilder.LineInput(it.text, it.isParagraphEnd)
                        })
                    val scope = CoroutineScope(Dispatchers.Default)
                    val base = awaitLayout(ChapterProvider.getTextChapterAsync(scope, book!!,
                        fixtureChapter, fixtureChapter.title, BookContent(false, contents, null),
                        fixtureChapter.index + 1, saveChapterData = false))
                    val fill = Color.rgb(32, 144, 80)
                    val style = HighlightStyle(bold = true, fill = fill,
                        fillShape = HighlightStyle.FillShape.PILL, pillPaddingScale = paddingScale)
                    val ranges = Regex("顶上").findAll(canonical(base)).map {
                        HighlightMatcher.Range(it.range.first, it.range.last + 1, style)
                    }.toList()
                    val spacing = HighlightSpacing.resolve(base, ranges)
                    assertTrue("Actual layout must reserve capsule space: $caseLabel", spacing.columns.isNotEmpty())
                    val spaced = awaitLayout(checkNotNull(base.layoutWithHighlightSpacing(scope, spacing)))
                    assertEquals("Spacing must preserve canonical anchors: $caseLabel", canonical(base), canonical(spaced))
                    assertEquals("Spacing must preserve title boundary: $caseLabel", base.layoutTitleLength, spaced.layoutTitleLength)
                    assertEquals("Spacing must be idempotent against the retained baseline: $caseLabel",
                        spacing, HighlightSpacing.resolve(checkNotNull(spaced.highlightSpacingBase), ranges))
                    assertEquals("All three real layout rows must fit this fixture: $caseLabel", 1, spaced.pageSize)
                    val page = spaced.pages.single()
                    val baselinePage = base.pages.single()
                    assertEquals("TEXT, native review and HTML rows must remain present: $caseLabel", 3, page.lines.size)
                    assertTrue("The first row must use a real native review column: $caseLabel",
                        page.lines.first().columns.last() is ReviewColumn)
                    assertTrue("The last row must use real HTML text columns: $caseLabel", page.lines.last().isHtml)
                    val originalColumns = base.pages.flatMap { it.lines }.flatMap { it.columns }
                    val spacedColumns = spaced.pages.flatMap { it.lines }.flatMap { it.columns }
                    assertEquals("Spacing must preserve logical columns: $caseLabel", originalColumns.size, spacedColumns.size)
                    originalColumns.zip(spacedColumns).forEach { (before, after) ->
                        assertEquals("Spacing must preserve image and glyph widths: $caseLabel",
                            before.end - before.start, after.end - after.start, 0.001f)
                    }
                    page.lines.forEach { line ->
                        assertTrue("Reserved slots must fit the content width: $caseLabel",
                            line.columns.last().end <= width - ChapterProvider.paddingRight + 1f)
                        line.columns.filter { it is ImageColumn || it is ReviewColumn }.forEach {
                            assertTrue("Touch coordinates must follow the drawn image: $caseLabel", it.isTouch((it.start + it.end) / 2f))
                        }
                    }
                    val height = ceil(maxOf(page.height, baselinePage.height)).toInt()
                    page.upRenderHeight()
                    page.isCompleted = true
                    val view = ContentTextView(activity, null).apply {
                        layout(0, 0, width, height)
                        setContent(page)
                    }
                    val columns = page.lines.flatMap { it.columns }
                    val positions = columns.map { it.start to it.end }
                    val textColumns = columns.filterIsInstance<TextBaseColumn>().filter { it.charData != " " }
                    fun render(color: Int, withFill: Boolean): Bitmap {
                        textColumns.forEach {
                            it.highlightStyle = HighlightStyle(textColor = color, bold = true,
                                fill = if (withFill) fill else 0, fillShape = HighlightStyle.FillShape.PILL,
                                pillPaddingScale = paddingScale)
                        }
                        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            .also { view.draw(Canvas(it)) }
                    }
                    val baselineView = ContentTextView(activity, null).apply {
                        layout(0, 0, width, height)
                        setContent(baselinePage)
                    }
                    baselinePage.lines.flatMap { it.columns }.filterIsInstance<TextBaseColumn>().forEach {
                        it.highlightStyle = HighlightStyle(textColor = 1, bold = true)
                    }
                    val baselineIcons = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        .also { baselineView.draw(Canvas(it)) }
                    val icons = render(1, false)
                    val glyphs = render(Color.BLACK, false)
                    val background = render(1, true)
                    val result = render(Color.BLACK, true)
                    val legacy = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val flattened = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        // Negative control: the original expanded capsule behind the same SVG pixels.
                        val canvas = Canvas(legacy)
                        for (line in baselinePage.lines) {
                            val highlighted = line.columns.filterIsInstance<TextBaseColumn>()
                                .filter { it.charData != " " }
                            val band = HighlightGeometry.fillBand(line.lineBase - line.lineTop,
                                textSize, line.height, HighlightStyle.FillShape.PILL, 1f.dpToPx())
                            val padding = (band.bottom - band.top) / 2
                            HighlightDraw.drawFillRun(canvas, highlighted.first().start - padding,
                                highlighted.last().end + padding, line.lineTop + band.top,
                                line.lineTop + band.bottom, fill, HighlightStyle.FillShape.PILL)
                            Canvas(flattened).apply {
                                save()
                                clipRect(line.columns.first().end, line.lineTop + band.top,
                                    line.columns.last().start, line.lineTop + band.bottom)
                                HighlightDraw.drawFillRun(this, highlighted.first().start - padding,
                                    highlighted.last().end + padding, line.lineTop + band.top,
                                    line.lineTop + band.bottom, fill, HighlightStyle.FillShape.PILL)
                                restore()
                            }
                        }
                        canvas.drawBitmap(baselineIcons, 0f, 0f, null)
                        Canvas(flattened).drawBitmap(baselineIcons, 0f, 0f, null)
                        for ((name, bitmap) in listOf("fixed" to result, "flattened" to flattened)) {
                            File(context.getExternalFilesDir("ui-regression"),
                                "highlight-transparent-$size-$optimized-$withSpace-$paddingScale-$iconScale-$name.png").outputStream().use {
                                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                            }
                        }
                        for ((row, line) in page.lines.withIndex()) {
                            for (icon in listOf(line.columns.first(), line.columns.last())) {
                                var transparent = 0
                                var visibleInk = 0
                                for (y in ceil(line.lineTop).toInt() until floor(line.lineBottom).toInt()) {
                                    for (x in icon.start.toInt() until icon.end.toInt()) {
                                        val before = icons.getPixel(x, y)
                                        if (Color.alpha(before) == 0) transparent++
                                        // Thin SVG strokes at small sizes are primarily antialiased.
                                        if (Color.alpha(before) >= 128) visibleInk++
                                        assertEquals("SVG changed: $caseLabel row=$row ($x,$y)",
                                            before, background.getPixel(x, y))
                                    }
                                }
                                assertTrue("The real SVG must retain transparent areas: $caseLabel row=$row x=${icon.start} count=$transparent",
                                    transparent > 100)
                                assertTrue("The real SVG must draw visible ink: $caseLabel row=$row x=${icon.start} count=$visibleInk",
                                    visibleInk > 10)
                            }
                            var ink = 0
                            val highlighted = line.columns.filterIsInstance<TextBaseColumn>()
                                .filter { it.charData != " " }
                            for (y in ceil(line.lineTop).toInt() until floor(line.lineBottom).toInt()) {
                                for (x in highlighted.first().start.toInt() until highlighted.last().end.toInt()) {
                                    if (Color.alpha(glyphs.getPixel(x, y)) < 240) continue
                                    ink++
                                    assertTrue("Adjacent icons must not push the cap through text glyphs: $caseLabel row=$row ($x,$y)",
                                        Color.alpha(background.getPixel(x, y)) in 70..110)
                                }
                            }
                            assertTrue("Both real Chinese glyphs must be rendered: $caseLabel row=$row", ink > 50)
                            run {
                                val band = HighlightGeometry.fillBand(line.lineBase - line.lineTop, textSize,
                                    line.height, HighlightStyle.FillShape.PILL, 1f.dpToPx())
                                val middle = (line.lineTop + (band.top + band.bottom) / 2f).toInt()
                                val shoulder = (line.lineTop + band.top + (band.bottom - band.top) * 0.08f).toInt()
                                fun edges(bitmap: Bitmap, y: Int): Pair<Int, Int> {
                                    val xs = ceil(line.columns.first().end).toInt() until floor(line.columns.last().start).toInt()
                                    fun filled(x: Int) = Color.alpha(bitmap.getPixel(x, y)) > 30 &&
                                        bitmap.getPixel(x, y) != icons.getPixel(x, y)
                                    return xs.first(::filled) to xs.last(::filled)
                                }
                                val centerEdges = edges(background, middle)
                                val shoulderEdges = edges(background, shoulder)
                                assertTrue("Left cap must be visibly curved: $caseLabel row=$row middle=$centerEdges shoulder=$shoulderEdges",
                                    shoulderEdges.first - centerEdges.first >= 2)
                                assertTrue("Right cap must be visibly curved: $caseLabel row=$row middle=$centerEdges shoulder=$shoulderEdges",
                                    centerEdges.second - shoulderEdges.second >= 2)
                            }
                        }
                        if (!withSpace) {
                            val line = baselinePage.lines[1]
                            val band = HighlightGeometry.fillBand(line.lineBase - line.lineTop,
                                textSize, line.height, HighlightStyle.FillShape.PILL, 1f.dpToPx())
                            val middle = (line.lineTop + (band.top + band.bottom) / 2f).toInt()
                            val shoulder = (line.lineTop + band.top + (band.bottom - band.top) * 0.08f).toInt()
                            fun oldEdges(y: Int): Pair<Int, Int> {
                                val xs = ceil(line.columns.first().end).toInt() until floor(line.columns.last().start).toInt()
                                fun filled(x: Int) = Color.alpha(flattened.getPixel(x, y)) > 30 &&
                                    flattened.getPixel(x, y) != baselineIcons.getPixel(x, y)
                                return xs.first(::filled) to xs.last(::filled)
                            }
                            val centerEdges = oldEdges(middle)
                            val shoulderEdges = oldEdges(shoulder)
                            assertTrue("Unspaced clipped caps must fail the same curvature check: $caseLabel",
                                shoulderEdges.first - centerEdges.first < 2 || centerEdges.second - shoulderEdges.second < 2)
                            for (icon in listOf(line.columns.first(), line.columns.last())) {
                                var overlap = 0
                                for (y in ceil(line.lineTop).toInt() until floor(line.lineBottom).toInt())
                                    for (x in ceil(icon.start).toInt() until floor(icon.end).toInt())
                                        if (legacy.getPixel(x, y) != baselineIcons.getPixel(x, y)) overlap++
                                assertTrue("Unspaced expanded caps must overlap the SVG: $caseLabel", overlap > 0)
                            }
                        }
                        assertEquals("Drawing must not mutate laid-out columns: $caseLabel", positions,
                            columns.map { it.start to it.end })
                        if (optimized && size == 50 && withSpace && paddingScale == 2f) {
                            view.setBackgroundColor(Color.WHITE)
                            activity.addContentView(view, ViewGroup.LayoutParams(width, height))
                            assertTrue("The final preview must use hardware rendering", view.isHardwareAccelerated)
                            hardwareView = view
                        }
                    } finally {
                        listOf(icons, glyphs, background, result, legacy, flattened, baselineIcons).forEach(Bitmap::recycle)
                        page.recycleRecorders()
                        baselinePage.recycleRecorders()
                    }
                }
            }
            screenshot("highlight-transparent-hardware")
        } finally {
            instrumentation.runOnMainSync {
                hardwareView?.let { (it.parent as? ViewGroup)?.removeView(it) }
                AppConfig.optimizeRender = savedOptimize
                ReadBookConfig.useZhLayout = savedZhLayout
                AppConfig.adaptSpecialStyle = savedAdaptStyle
                ChapterProvider.clearReviewProviders()
                ImageProvider.remove(imageFile.absolutePath)
            }
            imageFile.delete()
        }
    }

    @Test
    fun capsuleSpacingWrapsAndRepaginatesWithoutChangingTextOrAccumulatingInsets() {
        launchReader()
        val src = "https://fixture.invalid/issue1255-wrapping.svg"
        val imageFile = BookHelp.getImage(book!!, src)
        imageFile.parentFile!!.mkdirs()
        imageFile.writeText(instrumentation.context.assets.open("issue1255-transparent-cat.svg")
            .bufferedReader().use { it.readText() })
        val savedZhLayout = ReadBookConfig.useZhLayout
        val savedAdapt = AppConfig.adaptSpecialStyle
        val savedJustify = ReadBookConfig.textFullJustify
        try {
            scenario!!.onActivity {
                ReadBookConfig.titleMode = 2
                ReadBookConfig.paragraphIndent = ""
                ReadBookConfig.textSize = 20
                ReadBookConfig.reviewIconScale = 100
                AppConfig.adaptSpecialStyle = true
                ChapterProvider.upStyle()
                val chapter = BookChapter(bookUrl = book!!.bookUrl, url = "spacing-wrap", index = 10001)
                ChapterProvider.setReviewProviders({ _, _ -> 88 }, null, chapter.index)
                val paint = ChapterProvider.contentPaint
                val image = """<img src='$src,{"style":"text"}'>"""
                // Leave less than one glyph of spare width before reserving the capsule and review slot.
                val count = ((ChapterProvider.visibleWidth - paint.measureText("顶上") -
                    paint.measureText(ChapterProvider.srcReplaceStr)) / paint.measureText("前")).toInt() - 1
                assertTrue(count > 1)
                val paragraph = "前".repeat(count) + image + "顶上"
                fun canonical(chapter: TextChapter) = HighlightTextBuilder.build(chapter.pages
                    .flatMap { it.lines }.map { HighlightTextBuilder.LineInput(it.text, it.isParagraphEnd) })
                fun awaitLayout(chapter: TextChapter): TextChapter = runBlocking {
                    withTimeout(30_000) {
                        for (ignored in chapter.layoutChannel) Unit
                        while (!chapter.isCompleted) yield()
                    }
                    chapter
                }
                for (zhLayout in listOf(false, true)) for (justify in listOf(false, true)) {
                    ReadBookConfig.useZhLayout = zhLayout
                    context.putPrefBoolean(PreferKey.textFullJustify, justify)
                    val scope = CoroutineScope(Dispatchers.Default)
                    val base = awaitLayout(ChapterProvider.getTextChapterAsync(scope, book!!,
                        chapter, "Spacing", BookContent(false, List(90) { paragraph }, null),
                        chapter.index + 1, saveChapterData = false))
                    val originalText = canonical(base)
                    val style = HighlightStyle(fill = Color.GREEN,
                        fillShape = HighlightStyle.FillShape.PILL, pillPaddingScale = 2f)
                    val ranges = Regex("顶上").findAll(originalText).map {
                        HighlightMatcher.Range(it.range.first, it.range.last + 1, style)
                    }.toList()
                    val spacing = HighlightSpacing.resolve(base, ranges)
                    val spaced = awaitLayout(checkNotNull(base.layoutWithHighlightSpacing(scope, spacing)))
                    val repeated = awaitLayout(checkNotNull(spaced.layoutWithHighlightSpacing(scope, spacing)))
                    val restored = awaitLayout(checkNotNull(spaced.layoutWithHighlightSpacing(scope, HighlightSpacing())))
                    val caseLabel = "zh=$zhLayout justify=$justify"
                    assertEquals("Canonical text must survive wrapping: $caseLabel", originalText, canonical(spaced))
                    assertEquals("Canonical text must survive removing the style: $caseLabel", originalText, canonical(restored))
                    assertTrue("Extra measured advance must wrap lines: $caseLabel",
                        spaced.pages.sumOf { it.lines.size } > base.pages.sumOf { it.lines.size })
                    assertTrue("Wrapped lines must repaginate: $caseLabel", spaced.pageSize > base.pageSize)
                    fun geometry(chapter: TextChapter) = chapter.pages.flatMap { it.lines }.map { line ->
                        line.chapterPosition to line.columns.map { it.start to it.end }
                    }
                    assertEquals("Repeated layout must not add space again: $caseLabel", geometry(spaced), geometry(repeated))
                    assertEquals("Removing capsule styling must restore original layout: $caseLabel", geometry(base), geometry(restored))
                    val anchor = ranges[40].start
                    val anchoredPage = checkNotNull(spaced.getPageByReadPos(anchor))
                    assertTrue("Saved character position must select the same paragraph: $caseLabel",
                        anchoredPage.lines.any { anchor in it.chapterIndices })
                    assertEquals("Matched text must remain unchanged: $caseLabel", "顶上", canonical(spaced).substring(anchor, anchor + 2))
                    spaced.pages.flatMap { it.lines }.forEach { line ->
                        assertTrue("Wrapped text and native icons must fit the page: $caseLabel",
                            line.columns.last().end <= ChapterProvider.viewWidth - ChapterProvider.paddingRight + 1f)
                        line.columns.filter { it is ImageColumn || it is ReviewColumn }.forEach { column ->
                            assertTrue(column.isTouch((column.start + column.end) / 2f))
                        }
                    }
                    listOf(base, spaced, repeated, restored).forEach { it.pages.forEach(TextPage::recycleRecorders) }
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                ReadBookConfig.useZhLayout = savedZhLayout
                AppConfig.adaptSpecialStyle = savedAdapt
                context.putPrefBoolean(PreferKey.textFullJustify, savedJustify)
                ChapterProvider.clearReviewProviders()
                ImageProvider.remove(imageFile.absolutePath)
            }
            imageFile.delete()
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
        // Reveal the item within its horizontal scroller without starting Android's edge-back gesture.
        onView(withId(R.id.tv_tip)).inRoot(isDialog()).perform(scrollTo())
        onView(withId(R.id.tv_tip)).inRoot(isDialog()).check(matches(isCompletelyDisplayed()))
        screenshot("title-weight-information-entry")
        onView(withId(R.id.tv_tip)).inRoot(isDialog()).perform(click())
        onView(withId(R.id.ll_title_font_weight)).inRoot(isDialog()).check(matches(isDisplayed()))
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
        onView(withId(R.id.tv_tip)).inRoot(isDialog()).perform(scrollTo())
        onView(withId(R.id.tv_tip)).inRoot(isDialog())
            .check(matches(isCompletelyDisplayed())).perform(click())
        onView(withId(R.id.tv_title_font_weight)).inRoot(isDialog()).check(matches(withText(weights[0])))
        screenshot("title-weight-settings-restored")
        dismissSettings()
    }

    private fun chooseTitleWeight(label: String, setting: Int, weight: Int) {
        onView(withId(R.id.ll_title_font_weight)).inRoot(isDialog()).perform(click())
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
        onView(withId(R.id.ll_title_font_weight)).inRoot(isDialog()).perform(pressBack())
        onView(withId(R.id.tv_tip)).inRoot(isDialog()).perform(pressBack())
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
        val frames = CountDownLatch(1)
        scenario!!.onActivity { activity ->
            activity.window.decorView.postOnAnimation {
                activity.window.decorView.postOnAnimation { frames.countDown() }
            }
        }
        assertTrue("The screen must render after scrolling or changing settings", frames.await(5, TimeUnit.SECONDS))
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream()
                .use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
