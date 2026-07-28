package io.legado.app.ui.book.read.page.provider

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 段首标点悬挂后的断行宽度
 * 悬挂标点排在缩进内不占行宽,首行因此可以多排一个字
 */
class HangingLineWidthTest {

    /**全角字宽*/
    private val em = 24f
    private val visibleWidth = 240

    /**按 ZhLayout 的方式逐字贪心填行,超出的字下移到新行,返回每行字数*/
    private fun fillLines(widths: List<Float>, firstLineExtra: Float): List<Int> {
        val lines = ArrayList<Int>()
        var line = 0
        var lineW = 0f
        var count = 0
        widths.forEach { cw ->
            lineW += cw
            if (lineW > HangingLineWidth.lineCapacity(visibleWidth, firstLineExtra, line)) {
                lines.add(count)
                line++
                lineW = cw
                count = 1
            } else {
                count++
            }
        }
        if (count > 0) {
            lines.add(count)
        }
        return lines
    }

    @Test
    fun `only the first line gets the hanging width`() {
        assertEquals(264f, HangingLineWidth.lineCapacity(visibleWidth, em, 0), delta)
        assertEquals(240f, HangingLineWidth.lineCapacity(visibleWidth, em, 1), delta)
        assertEquals(240f, HangingLineWidth.lineCapacity(visibleWidth, em, 7), delta)
    }

    @Test
    fun `without hanging every line keeps the visible width`() {
        (0..3).forEach {
            assertEquals(240f, HangingLineWidth.lineCapacity(visibleWidth, 0f, it), delta)
        }
    }

    @Test
    fun `hanging pulls one more full width char onto the first line`() {
        val widths = List(30) { em }
        assertEquals(listOf(10, 10, 10), fillLines(widths, 0f))
        assertEquals(listOf(11, 10, 9), fillLines(widths, em))
    }

    @Test
    fun `static layout widens the first line and pulls the rest back`() {
        val layoutWidth = HangingLineWidth.layoutWidth(visibleWidth, em)
        assertEquals(264, layoutWidth)
        val indents = HangingLineWidth.rightIndents(visibleWidth, layoutWidth)
        assertArrayEquals(intArrayOf(0, 24), indents)
        // Layout 的每行可用宽度是排版宽度减去该行右缩进
        assertEquals(264, layoutWidth - indents[0])
        assertEquals(visibleWidth, layoutWidth - indents[1])
    }

    @Test
    fun `fractional hanging width rounds up so the first line keeps the extra char`() {
        // StaticLayout 只接受整数宽度,向下取整会让首行差不足1px而少排一个字
        assertEquals(265, HangingLineWidth.layoutWidth(visibleWidth, 24.9f))
        assertEquals(253, HangingLineWidth.layoutWidth(visibleWidth, 12.5f))
        assertEquals(241, HangingLineWidth.layoutWidth(visibleWidth, 0.4f))
    }

    @Test
    fun `the rounded first line never gets less room than the hanging frees`() {
        listOf(0.4f, 12.5f, 23.9f, 24f, 24.1f, 47.5f).forEach { extra ->
            val layoutWidth = HangingLineWidth.layoutWidth(visibleWidth, extra)
            assertTrue(
                "$extra lost room: $layoutWidth < ${visibleWidth + extra}",
                layoutWidth >= visibleWidth + extra
            )
            // 多出的部分不足1px
            assertTrue("$extra over-widened", layoutWidth < visibleWidth + extra + 1f)
        }
    }

    @Test
    fun `later lines are pulled back to the visible width whatever the rounding`() {
        listOf(0.4f, 12.5f, 24f, 24.9f).forEach { extra ->
            val layoutWidth = HangingLineWidth.layoutWidth(visibleWidth, extra)
            val indents = HangingLineWidth.rightIndents(visibleWidth, layoutWidth)
            assertEquals(visibleWidth, layoutWidth - indents[1])
        }
    }

    private companion object {
        const val delta = 1e-3f
    }
}
