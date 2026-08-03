package io.legado.app.ui.book.read.page.provider

import io.legado.app.ui.book.read.page.provider.PunctuationCompressRule.classClose
import io.legado.app.ui.book.read.page.provider.PunctuationCompressRule.classNone
import io.legado.app.ui.book.read.page.provider.PunctuationCompressRule.classOpen
import io.legado.app.ui.book.read.page.provider.PunctuationCompressRule.trimBoth
import io.legado.app.ui.book.read.page.provider.PunctuationCompressRule.trimLeft
import io.legado.app.ui.book.read.page.provider.PunctuationCompressRule.trimRight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标点挤压规则
 * 校验挤压位置、裁剪宽度与字形偏移,字形不得被压出列外或压到相邻字上
 */
class PunctuationCompressRuleTest {

    /**全角字宽*/
    private val em = 24f

    /**字形偏左的标点,如句号,字形在左下角*/
    private val inkLeft = Bearing(left = 1f, right = 12f)

    /**字形偏右的标点,如前引号*/
    private val inkRight = Bearing(left = 12f, right = 1f)

    /**字形居中的标点,如叹号*/
    private val inkMiddle = Bearing(left = 8f, right = 8f)

    private class Bearing(val left: Float, val right: Float)

    private fun trimWidth(bearing: Bearing, width: Float = em) =
        PunctuationCompressRule.trimWidth(width, em, bearing.left, bearing.right)

    private fun trimSide(bearing: Bearing) =
        PunctuationCompressRule.trimSide(bearing.left, bearing.right)

    private fun drawOffset(bearing: Bearing, width: Float = em) =
        PunctuationCompressRule.drawOffset(trimSide(bearing), trimWidth(bearing, width))

    /**挤压后字形的墨迹仍落在列内,且不越过列的左右边界*/
    private fun assertInkInsideColumn(bearing: Bearing) {
        val trim = trimWidth(bearing)
        val offset = drawOffset(bearing)
        val columnWidth = em - trim
        val inkStart = offset + bearing.left
        val inkEnd = offset + (em - bearing.right)
        assertTrue("ink starts at $inkStart, before the column", inkStart >= -delta)
        assertTrue("ink ends at $inkEnd, past $columnWidth", inkEnd <= columnWidth + delta)
    }

    // region 标点分类

    @Test
    fun `opening and closing punctuation are classified by line break side`() {
        assertEquals(classOpen, PunctuationCompressRule.charClass('“'))
        assertEquals(classOpen, PunctuationCompressRule.charClass('（'))
        assertEquals(classOpen, PunctuationCompressRule.charClass('《'))
        assertEquals(classClose, PunctuationCompressRule.charClass('。'))
        assertEquals(classClose, PunctuationCompressRule.charClass('，'))
        assertEquals(classClose, PunctuationCompressRule.charClass('」'))
        assertEquals(classClose, PunctuationCompressRule.charClass('！'))
    }

    @Test
    fun `ordinary characters and half width punctuation are not compressible`() {
        assertEquals(classNone, PunctuationCompressRule.charClass('我'))
        assertEquals(classNone, PunctuationCompressRule.charClass(','))
        assertEquals(classNone, PunctuationCompressRule.charClass('.'))
        assertEquals(classNone, PunctuationCompressRule.charClass(' '))
        // 省略号与破折号占两个字宽,压了会断开
        assertEquals(classNone, PunctuationCompressRule.charClass('…'))
        assertEquals(classNone, PunctuationCompressRule.charClass('—'))
    }

    @Test
    fun `a punctuation carrying a variation selector is not compressed`() {
        val cluster = "。︀"
        assertEquals(-1, PunctuationCompressRule.indexOfCluster(cluster))
        assertEquals(classNone, PunctuationCompressRule.classOf(-1))
    }

    @Test
    fun `cluster ranges distinguish plain punctuation from standardized variants`() {
        val text = "。︀「"
        assertEquals(-1, PunctuationCompressRule.indexOfCluster(text, 0, 2))
        assertEquals(
            PunctuationCompressRule.indexOf('「'),
            PunctuationCompressRule.indexOfCluster(text, 2, 3)
        )
    }

    @Test
    fun `a surrogate pair is not mistaken for punctuation`() {
        // 代理对的首字是高位代理,不在挤压表内
        assertEquals(-1, PunctuationCompressRule.indexOfCluster("😀"))
        assertEquals(classNone, PunctuationCompressRule.classOf(-1))
    }

    @Test
    fun `an empty cluster is not punctuation`() {
        assertEquals(-1, PunctuationCompressRule.indexOfCluster(""))
    }

    // endregion

    // region 相邻标点

    private fun compressAdjacent(prev: Char?, char: Char, next: Char?): Boolean {
        return PunctuationCompressRule.compressAdjacent(
            PunctuationCompressRule.charClass(char),
            prev?.let { PunctuationCompressRule.charClass(it) } ?: classNone,
            next?.let { PunctuationCompressRule.charClass(it) } ?: classNone
        )
    }

    @Test
    fun `a lone punctuation between words keeps its full width`() {
        assertFalse(compressAdjacent('说', '。', null))
        assertFalse(compressAdjacent('说', '，', '他'))
        assertFalse(compressAdjacent('说', '“', '他'))
    }

    @Test
    fun `closing followed by opening compresses both and frees one em`() {
        // 」「 各让出半个字宽
        assertTrue(compressAdjacent('话', '」', '「'))
        assertTrue(compressAdjacent('」', '「', '再'))
    }

    @Test
    fun `two closing punctuation compress only the first`() {
        // 。」 句号让出半个字宽,收尾的引号保持全角
        assertTrue(compressAdjacent('话', '。', '」'))
        assertFalse(compressAdjacent('。', '」', null))
    }

    @Test
    fun `two opening punctuation compress only the first`() {
        // （「 前一个让出半个字宽
        assertTrue(compressAdjacent('说', '（', '「'))
        assertFalse(compressAdjacent('（', '「', '话'))
    }

    @Test
    fun `an empty bracket pair is left alone`() {
        // （） 之间没有正文,压了会挤成一团
        assertFalse(compressAdjacent('说', '（', '）'))
        assertFalse(compressAdjacent('（', '）', '话'))
    }

    // endregion

    // region 裁剪宽度

    @Test
    fun `a full width punctuation is trimmed to half`() {
        assertEquals(em / 2, trimWidth(inkLeft), delta)
        assertEquals(em / 2, trimWidth(inkRight), delta)
        assertEquals(em / 2, trimWidth(inkMiddle), delta)
    }

    @Test
    fun `a punctuation narrower than an em is never trimmed`() {
        // 已是半角的标点再压就会挨上相邻的字
        assertEquals(0f, trimWidth(inkLeft, width = em / 2), delta)
        assertEquals(0f, trimWidth(inkMiddle, width = em * 0.6f), delta)
    }

    @Test
    fun `the trim never exceeds the blank space beside the glyph`() {
        // 字形居中且两侧空白不足半角时只能少压一点,否则字形会被压出列外
        val tight = Bearing(left = 4f, right = 4f)
        assertEquals(8f, trimWidth(tight), delta)
        assertInkInsideColumn(tight)
        // 字形铺满字框时完全不压
        val full = Bearing(left = 0f, right = 0f)
        assertEquals(0f, trimWidth(full), delta)
    }

    @Test
    fun `a centred glyph is trimmed from both sides`() {
        // 传统上句号在字框左下,但部分字体把它排在正中,此时两侧均裁
        val centredPeriod = Bearing(left = 7f, right = 7f)
        assertEquals(trimBoth, trimSide(centredPeriod))
        assertEquals(em / 2, trimWidth(centredPeriod), delta)
        assertInkInsideColumn(centredPeriod)
    }

    // endregion

    // region 字形偏移

    @Test
    fun `a left leaning glyph is trimmed on the right and stays put`() {
        assertEquals(trimRight, trimSide(inkLeft))
        assertEquals(0f, drawOffset(inkLeft), delta)
        assertInkInsideColumn(inkLeft)
    }

    @Test
    fun `a right leaning glyph moves left by the trimmed width`() {
        assertEquals(trimLeft, trimSide(inkRight))
        assertEquals(-em / 2, drawOffset(inkRight), delta)
        assertInkInsideColumn(inkRight)
    }

    @Test
    fun `a centred glyph moves left by half the trimmed width`() {
        assertEquals(trimBoth, trimSide(inkMiddle))
        assertEquals(-em / 4, drawOffset(inkMiddle), delta)
        assertInkInsideColumn(inkMiddle)
    }

    @Test
    fun `an untrimmed glyph is drawn at the column start`() {
        assertEquals(0f, PunctuationCompressRule.drawOffset(trimLeft, 0f), delta)
        assertEquals(0f, PunctuationCompressRule.drawOffset(trimBoth, 0f), delta)
        assertEquals(0f, drawOffset(inkLeft, width = em / 2), delta)
    }

    // endregion

    // region 行内排布

    /**
     * 挤压后逐列排布,校验列不重叠且行宽确实缩短
     * 相邻的 」「 各压半角,整行让出一个字宽
     */
    @Test
    fun `compressing an adjacent pair frees exactly one em on the line`() {
        val widths = FloatArray(6) { em }
        val trimmed = trimWidth(inkLeft) + trimWidth(inkRight)
        assertEquals(em, trimmed, delta)
        val lineWidth = widths.sum() - trimmed
        assertEquals(5 * em, lineWidth, delta)
    }

    @Test
    fun `columns stay in order after a right leaning glyph is pulled left`() {
        // 前引号压到半角后字形左移,它的墨迹不能盖住前一个字
        val trim = trimWidth(inkRight)
        val previousEnd = em
        val columnStart = previousEnd
        val inkStart = columnStart + drawOffset(inkRight) + inkRight.left
        assertTrue("ink at $inkStart runs back into the previous column", inkStart >= previousEnd)
    }

    // endregion

    private companion object {
        const val delta = 1e-3f
    }
}
