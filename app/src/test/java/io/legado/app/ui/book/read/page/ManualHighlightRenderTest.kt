package io.legado.app.ui.book.read.page

import io.legado.app.help.HighlightRuleMatcher.RuleMatch
import io.legado.app.help.HighlightStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManualHighlightRenderTest {

    @Test
    fun `manual ranges cover every text column but skip titles`() {
        val content = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/ContentTextView.kt")

        assertTrue(content.contains("ReadBook.anchoredHighlightsOfChapter(chapter, titleLength)"))
        assertTrue(content.contains("anchor.start + titleLength"))
        assertTrue(content.contains("anchor.end + titleLength"))
        assertTrue(content.contains(".lastOrNull { (_, anchor)"))
        assertTrue(content.contains("val pageBase = chapter.getReadLength(page.index)"))
        assertTrue(content.contains("val ruleRanges = ReadBook.ruleMatchesOfChapter(chapter)"))
        assertTrue(content.contains("line.columns.map { it.positionLength }"))
        assertTrue(content.contains("isTitle = line.isTitle"))
        assertTrue(content.contains("if (column is TextBaseColumn)"))
    }

    @Test
    fun `chapters without manual highlights skip full text reconstruction`() {
        val readBook = readProjectFile("src/main/java/io/legado/app/model/ReadBook.kt")
        val anchors = readBook.substringAfter("fun anchoredHighlightsOfChapter(")
            .substringBefore("fun addHighlight(")
        val emptyHighlights = anchors.indexOf(
            "val anchors = if (chapterHighlights.isEmpty())"
        )
        val rebuild = anchors.indexOf("chapterText(chapter)")
        val cache = anchors.indexOf("chapter.manualHighlightAnchors = anchors")

        assertTrue(emptyHighlights in 0 until rebuild)
        assertTrue(rebuild in 0 until cache)
    }

    @Test
    fun `layout captures the exact raw title prefix before body content`() {
        val layout = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/page/provider/TextChapterLayout.kt"
        )
        val capture = layout.indexOf("textChapter.layoutTitleLength =")
        val body = layout.indexOf("contents.forEach")

        assertTrue(capture in 0 until body)
        assertTrue(layout.contains("textPages.sumOf { it.text.length } + stringBuilder.length"))
        assertTrue(layout.contains("isTitle = isTitle"))
        assertTrue(layout.contains("isTitle = true"))
    }

    @Test
    fun `highlight creation rejects cross chapter selections`() {
        val content = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/ContentTextView.kt")

        assertTrue(content.contains("if (startPage.chapterIndex != endPage.chapterIndex) return null"))
        assertTrue(content.contains("if (startLine.isTitle || endLine.isTitle) return null"))
        assertTrue(content.contains("if (page.getLine(textPos.lineIndex).isTitle) return null"))
        assertTrue(content.contains("highlightSelectionEndLength(selectEnd.columnIndex)"))
        assertTrue(content.contains("layoutTitleLength = titleLength"))
        assertTrue(content.contains("bookUrl = book.bookUrl"))
        assertTrue(content.contains("chapterUrl = chapter.chapter.url"))
    }

    @Test
    fun `line start boundary does not read or include the previous column`() {
        var invoked = false

        val length = highlightSelectionEndLength(-1) {
            invoked = true
            4
        }

        assertEquals(0, length)
        assertFalse(invoked)
    }

    @Test
    fun `paragraph breaks alone cannot create an invisible highlight`() {
        assertFalse("".hasHighlightableText())
        assertFalse("\r\n".hasHighlightableText())
        assertTrue(" ".hasHighlightableText())
        assertTrue("text\n".hasHighlightableText())
    }

    @Test
    fun `html links and review columns keep click priority`() {
        val content = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/ContentTextView.kt")
        val click = content.indexOf("fun click(")
        val review = content.indexOf("is ReviewColumn ->", click)
        val html = content.indexOf("is TextHtmlColumn ->", review)
        val link = content.indexOf("if (linkUrl != null)", html)
        val highlight = content.indexOf("column.highlightStyle != null", html)
        val longPress = content.indexOf("fun longPress(")
        val longPressHtml = content.indexOf("is TextHtmlColumn ->", longPress)
        val manageHighlight = content.indexOf("notifyHighlightClick(", longPressHtml)
        val selectText = content.indexOf("column.selected = true", manageHighlight)

        assertTrue(review in 0 until html)
        assertTrue(link in html until highlight)
        assertTrue(manageHighlight in longPressHtml until selectText)
    }

    @Test
    fun `highlight actions support click long press and off modes`() {
        val content = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/ContentTextView.kt")
        val appConfig = readProjectFile("src/main/java/io/legado/app/help/config/AppConfig.kt")
        val preferences = readProjectFile("src/main/res/xml/pref_config_read.xml")
        val values = readProjectFile("src/main/res/values/array_values.xml")
        val longPress = content.substringAfter("fun longPress(").substringBefore("fun click(")
        val click = content.substringAfter("fun click(").substringBefore("fun selectText(")
        val notify = content.substringAfter("private fun notifyHighlightClick(")
            .substringBefore("private fun highlightAt(")
        val clickValue = values.indexOf("<item>click</item>")
        val longPressValue = values.indexOf("<item>longPress</item>")
        val offValue = values.indexOf("<item>off</item>")

        assertTrue(appConfig.contains("getPrefString(PreferKey.highlightActionTrigger, \"click\")"))
        assertTrue(preferences.contains("android:defaultValue=\"click\""))
        assertTrue(preferences.contains("android:key=\"highlightActionTrigger\""))
        assertTrue(clickValue in 0 until longPressValue)
        assertTrue(longPressValue in 0 until offValue)
        assertTrue(longPress.contains("highlightActionTrigger == \"longPress\""))
        assertTrue(click.contains("highlightActionTrigger != \"longPress\""))
        val offGate = notify.indexOf("if (AppConfig.highlightActionTrigger == \"off\") return false")
        val manual = notify.indexOf("highlightAt(column, textPos, page)?.let")
        val automatic = notify.indexOf("highlightRuleIdAt(column, textPos, page)?.let")
        assertTrue(offGate in 0 until manual)
        assertTrue(manual in 0 until automatic)
    }

    @Test
    fun `page changes dismiss visible highlight actions`() {
        val activity = readProjectFile("src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
        val popupMenu = readProjectFile("src/main/java/io/legado/app/ui/widget/PopupActionMenu.kt")
        val pageChanged = activity.substringAfter("override fun pageChanged()")
            .substringBefore("private fun upSeekBarProgress()")
        val onDestroy = activity.substringAfter("override fun onDestroy()")

        assertTrue(popupMenu.contains("fun show(anchor: View, onClick: (String) -> Unit): PopupAction"))
        assertTrue(popupMenu.contains("return PopupAction(context).apply"))
        assertTrue(activity.contains("highlightPopup = popupActionMenu(this)"))
        assertTrue(pageChanged.contains("highlightPopup?.dismiss()"))
        assertTrue(onDestroy.contains("highlightPopup?.dismiss()"))
    }

    @Test
    fun `automatic highlight clicks fall back to the visible matching rule`() {
        val content = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/ContentTextView.kt")
        val activity = readProjectFile("src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
        val notify = content.substringAfter("private fun notifyHighlightClick(")
            .substringBefore("private fun relativeOffset(")
        val manual = notify.indexOf("highlightAt(column, textPos, page)?.let")
        val automatic = notify.indexOf("highlightRuleIdAt(column, textPos, page)?.let")

        assertTrue(manual in 0 until automatic)
        assertTrue(notify.contains("callBack.onHighlightRuleClick(it, x, y)"))
        assertTrue(notify.contains("ReadBook.ruleMatchesOfChapter(chapter)"))
        assertTrue(notify.contains("highlightRangeIntersects("))
        assertTrue(notify.contains("highlightRuleIdAtColumn("))
        assertTrue(activity.contains("override fun onHighlightRuleClick(ruleId: Long"))
        assertTrue(activity.contains("HighlightRuleEditDialog.edit(ruleId)"))
        assertTrue(activity.contains("R.string.highlight_rule_disable"))
        assertTrue(activity.contains("copy(isEnabled = false)"))
        assertTrue(activity.contains("appDb.highlightRuleDao.update(rule)"))
        assertTrue(activity.contains("ReadBook.upHighlightRules()"))
    }

    @Test
    fun `click ranges use full half-open column intervals`() {
        assertTrue(highlightRangeIntersects(0, 2, 1, 2))
        assertFalse(highlightRangeIntersects(0, 1, 1, 2))
        assertFalse(highlightRangeIntersects(1, 1, 0, 2))
    }

    @Test
    fun `automatic click match respects title gate and last rule priority`() {
        val style = HighlightStyle(fill = 1)
        val matches = listOf(
            RuleMatch(1, 2, 1, style, applyToTitle = false, applyToBody = true),
            RuleMatch(1, 2, 2, style, applyToTitle = true, applyToBody = false)
        )

        assertEquals(1L, highlightRuleIdAtColumn(matches, 0, 2, isTitle = false))
        assertEquals(2L, highlightRuleIdAtColumn(matches, 0, 2, isTitle = true))
        assertNull(highlightRuleIdAtColumn(matches.take(1), 0, 2, isTitle = true))
        assertNull(highlightRuleIdAtColumn(matches.drop(1), 0, 2, isTitle = false))
        assertNull(highlightRuleIdAtColumn(matches, 2, 3, isTitle = false))
    }

    @Test
    fun `column drawing uses isolated temporary paint styles`() {
        val text = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/entities/column/TextColumn.kt")
        val html = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/entities/column/TextHtmlColumn.kt")
        val draw = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/HighlightDraw.kt")
        val provider = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/page/provider/ChapterProvider.kt"
        )

        assertTrue(text.contains("HighlightDraw.obtainTextPaint(textPaint, it, textColor, charData)"))
        assertTrue(text.contains("HighlightDraw::recycleTextPaint"))
        assertTrue(html.contains("HighlightDraw.obtainTextPaint(textPaint, it, textColor, charData)"))
        assertTrue(html.contains("HighlightDraw::recycleTextPaint"))
        assertTrue(text.contains("it.resolvedFontPath.isNotEmpty()"))
        assertTrue(html.contains("it.resolvedFontPath.isNotEmpty()"))
        assertTrue(draw.contains("ChapterProvider.getHighlightTypeface(style.resolvedFontPath)"))
        assertTrue(draw.contains("preserveTextAdvance(base, paint, text)"))
        assertTrue(draw.contains("base.typeface?.style ?: Typeface.NORMAL"))
        assertTrue(provider.contains("LruCache<String, TypefaceResult>(8)"))
        assertFalse(provider.contains("HashMap<String, Typeface?>"))
        assertTrue(draw.contains("ThreadLocal<DrawState>"))
        assertTrue(text.contains("it.shadow != null"))
        assertTrue(html.contains("it.shadow != null"))
        assertTrue(draw.contains("paint.setShadowLayer(it.radius, it.dx, it.dy, it.color)"))
    }

    @Test
    fun `shadow styles are normalized once and use padded page caches`() {
        val line = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/entities/TextLine.kt")
        val page = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/entities/TextPage.kt")
        val text = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/entities/column/TextColumn.kt")
        val html = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/entities/column/TextHtmlColumn.kt")
        val draw = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/HighlightDraw.kt")

        assertTrue(line.contains("style.shadow != null || style.resolvedFontPath.isNotEmpty()"))
        assertTrue(line.contains("AppConfig.optimizeRender && !hasOverflowTextStyle"))
        assertTrue(page.contains("private val recordPadding = maxOf(21, 10.dpToPx())"))
        assertTrue(page.contains("view.width + recordPadding * 2"))
        assertTrue(page.contains("renderHeight + recordPadding * 2"))
        assertTrue(page.contains("-recordPadding.toFloat()"))
        assertTrue(
            page.contains("withTranslation(recordPadding.toFloat(), recordPadding.toFloat())")
        )
        assertTrue(page.contains("recordIfCompleted(view)"))
        assertFalse(page.contains("if (hasShadowStyle) return false"))
        assertFalse(page.contains("AppConfig.optimizeRender && !hasShadowStyle"))
        assertTrue(text.contains("val normalized = value?.normalized()"))
        assertTrue(html.contains("val normalized = value?.normalized()"))
        assertFalse(draw.contains("shadow?.normalized()"))
    }

    @Test
    fun `custom font advance scaling keeps layout width`() {
        assertEquals(0.5f, HighlightDraw.textAdvanceScale(10f, 20f), 0f)
        assertEquals(1f, HighlightDraw.textAdvanceScale(0f, 20f), 0f)
        assertEquals(1f, HighlightDraw.textAdvanceScale(10f, 0f), 0f)
        assertEquals(1f, HighlightDraw.textAdvanceScale(Float.NaN, 20f), 0f)
    }

    @Test
    fun `underline variants use configurable baseline geometry`() {
        val draw = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/HighlightDraw.kt")

        assertFalse(draw.contains("DashPathEffect"))
        assertTrue(draw.contains("underline?.normalized()?.takeIf { it.width > 0f }"))
        assertTrue(draw.contains("val width = it.width.dpToPx()"))
        assertTrue(draw.contains("val y = baseline + it.distance.dpToPx()"))
        assertTrue(draw.contains("while (start < x1)"))
        assertTrue(draw.contains("canvas.drawCircle(center, y, radius, fillPaint)"))
    }

    @Test
    fun `underline takes drawing priority over emphasis`() {
        val text = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/page/entities/column/TextColumn.kt"
        )
        val html = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/page/entities/column/TextHtmlColumn.kt"
        )
        val guardedEmphasis = "style?.takeIf { it.underline == null }?.emphasis?.let"

        assertTrue(text.contains(guardedEmphasis))
        assertTrue(html.contains(guardedEmphasis))
    }

    @Test
    fun `fill shapes share one run renderer across fast and styled text`() {
        val line = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/entities/TextLine.kt")
        val text = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/entities/column/TextColumn.kt")
        val html = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/entities/column/TextHtmlColumn.kt")
        val draw = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/HighlightDraw.kt")

        assertTrue(line.indexOf("drawHighlightFills(canvas)") < line.indexOf("checkFastDraw()"))
        assertTrue(line.contains("nextStyle.resolvedFillShape == shape"))
        assertTrue(line.contains("nextTextSize == textSize"))
        assertTrue(draw.contains("fun drawFillRun("))
        assertTrue(draw.contains("val inset = strokePaint.strokeWidth / 2f"))
        assertTrue(draw.contains("top + inset"))
        assertTrue(draw.contains("bottom - inset"))
        assertFalse(text.contains("highlightPaint("))
        assertFalse(html.contains("highlightPaint("))
    }

    @Test
    fun `run decorations follow html text size`() {
        val line = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/entities/TextLine.kt")
        val draw = readProjectFile("src/main/java/io/legado/app/ui/book/read/page/HighlightDraw.kt")

        assertTrue(line.contains("val sizeSensitive = strike != null || box != null"))
        assertTrue(line.contains("val sameTextSize = !sizeSensitive ||"))
        assertTrue(line.contains("val metricScale = textSize / baseTextSize"))
        assertTrue(draw.contains("if (right > left && bottom > top)"))
    }

    @Test
    fun `html horizontal rules consume one chapter position`() {
        val column = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/page/entities/column/BaseColumn.kt"
        )
        val baseColumn = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/page/entities/column/TextBaseColumn.kt"
        )
        val htmlColumn = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/page/entities/column/TextHtmlColumn.kt"
        )
        val page = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/page/entities/TextPage.kt"
        )

        assertTrue(column.contains("val positionLength: Int get() = 0"))
        assertTrue(baseColumn.contains("override val positionLength: Int get() = charData.length"))
        assertTrue(htmlColumn.contains("charData == HR_PLACE_STR"))
        assertTrue(htmlColumn.contains("HR_PLACE_CHAR.length"))
        assertTrue(page.contains("length += columns[index].positionLength"))
    }

    @Test
    fun `inline images consume one chapter position without counting review controls`() {
        val image = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/page/entities/column/ImageColumn.kt"
        )
        val content = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/page/ContentTextView.kt"
        )

        assertTrue(image.contains("override val positionLength: Int = 1"))
        assertTrue(content.contains("line.columns.map { it.positionLength }"))
    }

    private fun readProjectFile(pathInApp: String): String {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .first(File::isFile)
            .readText()
    }
}
