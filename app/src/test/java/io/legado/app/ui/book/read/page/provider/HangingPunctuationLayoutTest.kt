package io.legado.app.ui.book.read.page.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 段首标点悬挂的排版结果
 * 校验首字坐标、行尾坐标、列区域不重叠,覆盖自然排版/两端对齐/中文全角/点击坐标
 */
class HangingPunctuationLayoutTest {

    /**全角字宽*/
    private val em = 24f
    private val halfEm = 12f
    private val indentLength = 2
    private val visibleWidth = 10 * em

    private class Column(val index: Int, val start: Float, val end: Float, val kind: Int) {
        val width get() = end - start
        override fun toString() = "[$index]($start,$end)k$kind"
    }

    private class Line {
        val columns = ArrayList<Column>()
        var indentWidth = 0f
        var startX = 0f
        var gap = 0f
        var isWordSpacing = false
        val lineStart get() = columns.first().start
        val lineEnd get() = columns.last().end
        val hanging get() = columns.singleOrNull { it.kind == LineColumnLayout.kindHanging }
        fun body(index: Int) = columns[index]
    }

    private fun naturalLine(
        widths: List<Float>,
        startX: Float = 0f,
        hasIndent: Boolean = true,
        hangingWidth: Float = 0f
    ) = Line().also { line ->
        LineColumnLayout.natural(
            widths, startX, hasIndent, indentLength, hangingWidth,
            onIndentWidth = { line.indentWidth = it }
        ) { index, xStart, xEnd, kind ->
            line.columns.add(Column(index, xStart, xEnd, kind))
        }
    }

    private fun justifiedFirstLine(
        widths: List<Float>,
        hangingWidth: Float = 0f,
        indentCharWidth: Float = em,
        words: List<String> = List(widths.size) { "字" }
    ) = Line().also { line ->
        LineColumnLayout.justifiedFirst(
            words, widths, visibleWidth, widths.sum(),
            indentLength, indentCharWidth, hangingWidth,
            onIndentWidth = { line.indentWidth = it },
            onJustify = { startX, gap, isWordSpacing ->
                line.startX = startX
                line.gap = gap
                line.isWordSpacing = isWordSpacing
            }
        ) { index, xStart, xEnd, kind ->
            line.columns.add(Column(index, xStart, xEnd, kind))
        }
    }

    /**列按顺序排布且互不重叠,否则点击会命中错误的字*/
    private fun assertNoOverlap(line: Line) {
        line.columns.forEach {
            assertTrue("$it is inverted", it.end >= it.start - delta)
        }
        for (i in 1 until line.columns.size) {
            val prev = line.columns[i - 1]
            val current = line.columns[i]
            assertTrue(
                "$current overlaps $prev",
                current.start >= prev.end - delta
            )
        }
    }

    /**与 ContentTextView 一致: 正序遍历,取第一个 start < x < end 的列*/
    private fun touchedIndex(line: Line, x: Float): Int {
        return line.columns.indexOfFirst { x > it.start && x < it.end }
    }

    // region 自然排版

    @Test
    fun `natural line without hanging is contiguous from the origin`() {
        val line = naturalLine(listOf(em, em, em, em, em))
        assertEquals(0f, line.lineStart, delta)
        assertEquals(5 * em, line.lineEnd, delta)
        assertEquals(2 * em, line.indentWidth, delta)
        assertNoOverlap(line)
    }

    @Test
    fun `natural hanging aligns the first body char with a plain paragraph`() {
        val plain = naturalLine(listOf(em, em, em, em))
        val quoted = naturalLine(listOf(em, em, em, em, em), hangingWidth = em)
        // 悬挂后 “ 落在缩进内,正文首字与无引号段落的首字同列
        assertEquals(plain.body(indentLength).start, quoted.body(indentLength + 1).start, delta)
        assertEquals(2 * em, quoted.body(indentLength + 1).start, delta)
        // 悬挂标点不再占用行内宽度,行尾与无引号段落一致
        assertEquals(plain.lineEnd, quoted.lineEnd, delta)
        assertNoOverlap(quoted)
    }

    @Test
    fun `natural hanging column occupies the last indent cell`() {
        val line = naturalLine(listOf(em, em, em, em, em), hangingWidth = em)
        val hanging = requireNotNull(line.hanging)
        assertEquals(em, hanging.start, delta)
        assertEquals(2 * em, hanging.end, delta)
        // 被覆盖的缩进列收缩为零宽,让出点击区域
        assertEquals(0f, line.columns[0].start, delta)
        assertEquals(em, line.columns[0].end, delta)
        assertEquals(em, line.columns[1].start, delta)
        assertEquals(em, line.columns[1].end, delta)
        assertNoOverlap(line)
    }

    @Test
    fun `natural hanging wider than one indent char still keeps columns apart`() {
        // 缩进为两个半角空格而标点是全角时,悬挂宽度会跨过一个以上的缩进列
        val quoteWidth = 1.5f * em
        val widths = listOf(em, em, quoteWidth, em, em)
        val line = naturalLine(widths, hangingWidth = quoteWidth)
        val hanging = requireNotNull(line.hanging)
        assertEquals(0.5f * em, hanging.start, delta)
        assertEquals(2 * em, hanging.end, delta)
        assertNoOverlap(line)
        assertTrue("indent columns must not reach into the hanging char", line.columns
            .filter { it.kind == LineColumnLayout.kindIndent }
            .all { it.end <= hanging.start + delta })
    }

    @Test
    fun `wrapped lines never hang because they have no indent`() {
        val line = naturalLine(listOf(em, em, em), hasIndent = false, hangingWidth = em)
        assertNull(line.hanging)
        assertEquals(0f, line.lineStart, delta)
        assertEquals(3 * em, line.lineEnd, delta)
        assertNoOverlap(line)
    }

    @Test
    fun `natural layout keeps a non zero start offset such as a centered title`() {
        val startX = 3 * em
        val line = naturalLine(listOf(em, em), startX = startX, hasIndent = false)
        assertEquals(startX, line.lineStart, delta)
        assertEquals(startX + 2 * em, line.lineEnd, delta)
    }

    // endregion

    // region 两端对齐

    @Test
    fun `justified first line without hanging fills the visible width`() {
        val line = justifiedFirstLine(List(8) { em })
        assertEquals(0f, line.lineStart, delta)
        assertEquals(visibleWidth, line.lineEnd, delta)
        assertEquals(2 * em, line.body(indentLength).start, delta)
        assertEquals(2 * em, line.indentWidth, delta)
        assertNoOverlap(line)
    }

    @Test
    fun `justified hanging keeps the line end at the visible width`() {
        val line = justifiedFirstLine(List(9) { em }, hangingWidth = em)
        assertEquals(visibleWidth, line.lineEnd, delta)
        // 正文首字与无引号段落同列
        assertEquals(2 * em, line.body(indentLength + 1).start, delta)
        assertEquals(2 * em, line.startX, delta)
        val hanging = requireNotNull(line.hanging)
        assertEquals(em, hanging.start, delta)
        assertEquals(2 * em, hanging.end, delta)
        assertNoOverlap(line)
    }

    @Test
    fun `justified hanging widens the gap instead of shrinking the line`() {
        val plain = justifiedFirstLine(List(9) { em })
        val quoted = justifiedFirstLine(List(9) { em }, hangingWidth = em)
        assertEquals(plain.lineEnd, quoted.lineEnd, delta)
        // 悬挂让出一个字宽,由剩余的间隙均分
        assertTrue("$quoted gap ${quoted.gap} <= ${plain.gap}", quoted.gap > plain.gap)
    }

    @Test
    fun `justified hanging does not move the line end when indent widths disagree`() {
        // indentCharWidth 由 getDesiredWidth 得出,可能与逐字测量的缩进宽度不同
        val widths = List(9) { em }
        val plain = justifiedFirstLine(widths, indentCharWidth = em + 2f)
        val quoted = justifiedFirstLine(widths, hangingWidth = em, indentCharWidth = em + 2f)
        assertEquals(plain.lineEnd, quoted.lineEnd, delta)
    }

    @Test
    fun `justified hanging keeps the word spacing branch filling the width`() {
        val words = listOf("　", "　", "\"", "a", " ", "b", " ", "c", " ", "d")
        val widths = listOf(em, em, halfEm, halfEm, halfEm, halfEm, halfEm, halfEm, halfEm, halfEm)
        val line = justifiedFirstLine(widths, hangingWidth = halfEm, words = words)
        assertTrue("residual width must go to the spaces", line.isWordSpacing)
        assertEquals(visibleWidth, line.lineEnd, delta)
        assertEquals(2 * em, line.body(indentLength + 1).start, delta)
        assertNoOverlap(line)
    }

    @Test
    fun `justified line without a body still lays out the indent`() {
        val line = justifiedFirstLine(listOf(em, em), hangingWidth = em)
        assertEquals(indentLength, line.columns.size)
        assertNull(line.hanging)
        assertEquals(2 * em, line.indentWidth, delta)
        assertNoOverlap(line)
    }

    // endregion

    // region 中文全角排版

    @Test
    fun `full width layout keeps every column on the character grid`() {
        val widths = List(9) { em }
        val natural = naturalLine(widths, hangingWidth = em)
        natural.columns.drop(indentLength + 1).forEach {
            assertEquals("$it is off the grid", 0f, it.start % em, delta)
            assertEquals(em, it.width, delta)
        }
        val justified = justifiedFirstLine(widths, hangingWidth = em)
        assertEquals(2 * em, justified.body(indentLength + 1).start, delta)
        assertEquals(visibleWidth, justified.lineEnd, delta)
    }

    @Test
    fun `zero width clusters after the hanging char do not shift the body`() {
        // measureTextSplit 会把零宽字符并入前一个 cluster, 宽度列表仍逐列对应
        val widths = listOf(em, em, em, em, 0f, em)
        val line = naturalLine(widths, hangingWidth = em)
        assertEquals(2 * em, line.body(indentLength + 1).start, delta)
        assertEquals(4 * em, line.lineEnd, delta)
        assertNoOverlap(line)
    }

    // endregion

    // region 点击坐标

    @Test
    fun `tapping the hanging punctuation selects it instead of the indent`() {
        val line = naturalLine(listOf(em, em, em, em, em), hangingWidth = em)
        val hangingIndex = line.columns.indexOfFirst { it.kind == LineColumnLayout.kindHanging }
        assertEquals(hangingIndex, touchedIndex(line, 1.5f * em))
        assertEquals(hangingIndex, touchedIndex(line, em + 1f))
        // 缩进首列仍可命中,正文首字不被悬挂列吞掉
        assertEquals(0, touchedIndex(line, 0.5f * em))
        assertEquals(hangingIndex + 1, touchedIndex(line, 2.5f * em))
    }

    @Test
    fun `tapping a justified line after hanging selects the right body char`() {
        val line = justifiedFirstLine(List(9) { em }, hangingWidth = em)
        val hangingIndex = line.columns.indexOfFirst { it.kind == LineColumnLayout.kindHanging }
        assertEquals(hangingIndex, touchedIndex(line, 1.5f * em))
        val firstBody = line.body(indentLength + 1)
        assertEquals(indentLength + 1, touchedIndex(line, (firstBody.start + firstBody.end) / 2))
        val lastBody = line.columns.last()
        assertEquals(
            line.columns.lastIndex,
            touchedIndex(line, (lastBody.start + lastBody.end) / 2)
        )
    }

    // endregion

    // region 下划线起点

    @Test
    fun `underline starts at the hanging punctuation`() {
        // 下划线由 lineStart + indentWidth 画起,悬挂标点是正文的一部分,要被划到
        val natural = naturalLine(listOf(em, em, em, em, em), hangingWidth = em)
        assertEquals(requireNotNull(natural.hanging).start, natural.indentWidth, delta)
        val justified = justifiedFirstLine(List(9) { em }, hangingWidth = em)
        assertEquals(requireNotNull(justified.hanging).start, justified.indentWidth, delta)
    }

    @Test
    fun `underline still skips the indent when nothing hangs`() {
        assertEquals(2 * em, naturalLine(listOf(em, em, em, em)).indentWidth, delta)
        assertEquals(2 * em, justifiedFirstLine(List(8) { em }).indentWidth, delta)
    }

    // endregion

    // region 悬挂宽度判定

    @Test
    fun `hanging width is the punctuation width when it fits the indent`() {
        val widths = floatArrayOf(em, em, em, em)
        assertEquals(em, LineColumnLayout.hangingWidth(widths, indentLength, em), delta)
    }

    @Test
    fun `punctuation wider than the indent is not hung`() {
        val widths = floatArrayOf(halfEm, halfEm, 2 * em, em)
        assertEquals(0f, LineColumnLayout.hangingWidth(widths, indentLength, halfEm), delta)
    }

    @Test
    fun `hanging width uses the narrower of measured and justified indent`() {
        // 两端对齐按 indentCharWidth 排布缩进,悬挂不能超出这个宽度
        val widths = floatArrayOf(em, em, em, em)
        assertEquals(0f, LineColumnLayout.hangingWidth(widths, indentLength, halfEm / 2), delta)
    }

    @Test
    fun `zero width punctuation is not hung`() {
        val widths = floatArrayOf(em, em, 0f, em)
        assertEquals(0f, LineColumnLayout.hangingWidth(widths, indentLength, em), delta)
    }

    // endregion

    private companion object {
        const val delta = 1e-3f
    }
}
