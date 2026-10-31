package io.legado.app.ui.book.read

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.paragraph.ParagraphAloudHighlight
import io.legado.app.ui.book.read.paragraph.ParagraphItem
import io.legado.app.ui.book.read.paragraph.ParagraphPositionRange
import io.legado.app.ui.book.read.paragraph.ParagraphReadView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real native text rendering; no service flags, database books or speech engine are faked. */
@RunWith(AndroidJUnit4::class)
class ParagraphReadHighlightUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val savedEInkMode = AppConfig.isEInkMode
    private lateinit var reader: ParagraphReadView
    private lateinit var list: RecyclerView
    private lateinit var chapter: TextChapter

    @Before fun setUp() = instrumentation.runOnMainSync {
        AppConfig.isEInkMode = false
        reader = ParagraphReadView(ContextThemeWrapper(instrumentation.targetContext, R.style.AppTheme_Light))
        list = reader.getChildAt(0) as RecyclerView
    }

    @After fun tearDown() = instrumentation.runOnMainSync {
        if (::reader.isInitialized) {
            reader.updateReadAloudHighlight(null)
            // Recycle holders even though this focused fixture never creates an activity window.
            list.adapter = null
        }
        AppConfig.isEInkMode = savedEInkMode
    }

    @Test fun speechProgressColorsNativeTextWithoutRebindingOrMovingTheList() = onMain {
        show(listOf(item("first", "第一段 A😀BCDEFG", 10), item("second", "第二段 HIJKLMN", 100)))
        var manualScrolls = 0
        reader.onUserScroll = { manualScrolls++ }
        val first = textAt(0)
        val second = textAt(1)
        val buffer = first.text
        val firstPosition = manager.findFirstVisibleItemPosition()
        val firstTop = list.getChildAt(0).top

        highlight("first", 10)
        assertColor(first, 0, first.length())
        assertNoAloudColor(second)

        // A system-TTS range callback advances within the same displayed paragraph.
        highlight("first", 17)
        assertColor(first, 7, first.length())
        assertSame("Progress must mutate the existing text buffer", buffer, first.text)
        assertEquals(firstPosition, manager.findFirstVisibleItemPosition())
        assertEquals(firstTop, list.getChildAt(0).top)
        assertEquals("Painting speech progress must not detach list following", 0, manualScrolls)

        // An HTTP speech-chunk transition can instead select the next complete display item.
        highlight("second", 100)
        assertNoAloudColor(first)
        assertColor(second, 0, second.length())
        assertEquals(firstPosition, manager.findFirstVisibleItemPosition())
        assertEquals(0, manualScrolls)

        // Pause/stop both synchronize an absent highlight into the native view.
        reader.updateReadAloudHighlight(null)
        assertNoAloudColor(first)
        assertNoAloudColor(second)
        assertSame(buffer, first.text)
    }

    @Test fun titleAndLongHtmlParagraphUseUtf16CoordinatesWithoutSplittingEmoji() = onMain {
        val value = "序章A😀BC" + "长段落内容".repeat(300)
        show(listOf(ParagraphItem(
            id = "long-title",
            text = value,
            chapterPosition = 50,
            isTitle = true,
            positionRanges = listOf(
                ParagraphPositionRange(0, 3, 50),
                ParagraphPositionRange(3, value.length, 500),
            ),
        )))
        // The callback lands on the low surrogate in the second, discontinuous HTML range.
        highlight("long-title", 501)
        val text = textAt(0)
        assertColor(text, 3, value.length)
        val span = colors(text).single()
        assertFalse(Character.isLowSurrogate(text.text[(text.text as Spanned).getSpanStart(span)]))

        // A later chunk of the same long paragraph still colors through the full display item.
        highlight("long-title", 750)
        assertColor(text, 253, value.length)
        reader.updateReadAloudHighlight(null)
        assertNoAloudColor(text)
    }

    @Test fun clearingAndRebindingPreserveSourceFormattingAndRejectOtherChapters() = onMain {
        val originalColor = ForegroundColorSpan(Color.BLUE)
        val originalUnderline = UnderlineSpan()
        val styled = SpannableString("source formatting and speech progress").apply {
            setSpan(originalColor, 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(originalUnderline, 1, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        show(listOf(item("same-id", styled, 20)))
        highlight("same-id", 24)
        val text = textAt(0)
        assertEquals(2, colors(text).size)
        assertTrue(colors(text).contains(originalColor))
        assertTrue(underlines(text).contains(originalUnderline))

        reader.updateReadAloudHighlight(null)
        assertEquals(listOf(originalColor), colors(text))
        assertEquals(listOf(originalUnderline), underlines(text))

        highlight("same-id", 24)
        val previousChapter = chapter
        show(listOf(item("same-id", "replacement chapter", 20)), chapterUrl = "other-chapter")
        assertNoAloudColor(textAt(0))
        reader.updateReadAloudHighlight(ParagraphAloudHighlight(
            previousChapter.chapter.bookUrl, previousChapter.chapter.url, "same-id", 24,
        ))
        assertNoAloudColor(textAt(0))
        reader.updateReadAloudHighlight(ParagraphAloudHighlight(
            "other-book", chapter.chapter.url, "same-id", 24,
        ))
        assertNoAloudColor(textAt(0))

        highlight("same-id", 24)
        assertColor(textAt(0), 4, textAt(0).length())
        reader.showMessage("Loading another chapter")
        show(listOf(item("same-id", "replacement chapter", 20)), chapterUrl = "other-chapter")
        assertNoAloudColor(textAt(0))
    }

    @Test fun recycledItemsRestoreOnlyTheCurrentHighlightAndEInkUsesAnUnderline() = onMain {
        val items = (0 until 40).map { item("paragraph-$it", "Paragraph $it with independent text", it * 100) }
        list.setItemViewCacheSize(40)
        show(items)
        highlight("paragraph-0", 3)
        assertColor(textAt(0), 3, items[0].text.length)

        list.scrollToPosition(30)
        layoutReader()
        assertTrue(manager.findFirstVisibleItemPosition() > 0)
        visibleText().forEach(::assertNoAloudColor)
        list.scrollToPosition(0)
        layoutReader()
        assertColor(textAt(0), 3, items[0].text.length)

        AppConfig.isEInkMode = true
        reader.upStyle()
        layoutReader()
        val inkText = textAt(0)
        assertColor(inkText, 3, items[0].text.length)
        val underline = underlines(inkText).single()
        val inkBuffer = inkText.text as Spanned
        assertEquals(3, inkBuffer.getSpanStart(underline))
        assertEquals(items[0].text.length, inkBuffer.getSpanEnd(underline))

        AppConfig.isEInkMode = false
        reader.upStyle()
        layoutReader()
        assertTrue(underlines(textAt(0)).isEmpty())
        assertColor(textAt(0), 3, items[0].text.length)
        list.scrollToPosition(30)
        layoutReader()
        // Clear while the old highlighted holder is cached rather than rebound.
        reader.updateReadAloudHighlight(null)
        list.scrollToPosition(0)
        layoutReader()
        assertNoAloudColor(textAt(0))

        highlight("paragraph-0", 3)
        list.scrollToPosition(30)
        layoutReader()
        highlight("paragraph-30", 3005)
        assertColor(textAt(30), 5, items[30].text.length)
        list.scrollToPosition(0)
        layoutReader()
        assertNoAloudColor(textAt(0))
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private val manager get() = list.layoutManager as LinearLayoutManager

    private fun item(id: String, text: CharSequence, start: Int) = ParagraphItem(
        id = id,
        text = text,
        chapterPosition = start,
        positionRanges = listOf(ParagraphPositionRange(0, text.length, start)),
    )

    private fun show(items: List<ParagraphItem>, chapterUrl: String = "highlight-chapter") {
        chapter = TextChapter(
            chapter = BookChapter(bookUrl = "highlight-book", url = chapterUrl, title = "Highlight fixture"),
            position = 0,
            title = "Highlight fixture",
            chaptersSize = 1,
            sameTitleRemoved = false,
            isVip = false,
            isPay = false,
            effectiveReplaceRules = null,
        ).also {
            it.paragraphItems = items
            it.isCompleted = true
        }
        reader.setChapter(chapter, items.firstOrNull()?.id, items.firstOrNull()?.chapterPosition ?: 0)
        layoutReader()
    }

    private fun layoutReader() {
        reader.measure(
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
        )
        reader.layout(0, 0, 720, 600)
    }

    private fun highlight(itemId: String, position: Int) {
        reader.updateReadAloudHighlight(ParagraphAloudHighlight(
            chapter.chapter.bookUrl, chapter.chapter.url, itemId, position,
        ))
    }

    private fun textAt(position: Int): TextView {
        val row = manager.findViewByPosition(position) as? ViewGroup
            ?: throw AssertionError("Paragraph $position is not laid out")
        return (0 until row.childCount).map(row::getChildAt).filterIsInstance<TextView>().single()
    }

    private fun visibleText(): List<TextView> = (0 until list.childCount).map { index ->
        val row = list.getChildAt(index) as ViewGroup
        (0 until row.childCount).map(row::getChildAt).filterIsInstance<TextView>().single()
    }

    private fun colors(text: TextView): List<ForegroundColorSpan> {
        val value = text.text as Spanned
        return value.getSpans(0, value.length, ForegroundColorSpan::class.java).toList()
    }

    private fun underlines(text: TextView): List<UnderlineSpan> {
        val value = text.text as Spanned
        return value.getSpans(0, value.length, UnderlineSpan::class.java).toList()
    }

    private fun assertColor(text: TextView, start: Int, end: Int) {
        val value = text.text as Spanned
        val color = colors(text).single()
        assertEquals(ReadBookConfig.textAccentColor, color.foregroundColor)
        assertEquals(start, value.getSpanStart(color))
        assertEquals(end, value.getSpanEnd(color))
        assertEquals(ReadBookConfig.textColor, text.currentTextColor)
        assertTrue(text.text is Spannable)
    }

    private fun assertNoAloudColor(text: TextView) {
        assertTrue("Ordinary text must not keep another paragraph's speech color", colors(text).isEmpty())
        assertEquals(ReadBookConfig.textColor, text.currentTextColor)
    }
}
