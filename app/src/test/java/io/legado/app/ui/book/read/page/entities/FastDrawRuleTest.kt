package io.legado.app.ui.book.read.page.entities

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整行一次性绘制的退回条件
 * fastDrawTextLine 用一次 drawText 按字宽顺序排字,改写过列坐标的行必须逐列绘制
 */
class FastDrawRuleTest {

    private fun canDrawWholeLine(
        optimizeRender: Boolean = true,
        exceed: Boolean = false,
        hangingPunctuation: Boolean = false,
        compressedPunctuation: Boolean = false,
        onlyTextColumn: Boolean = true,
        isMsgPage: Boolean = false
    ) = FastDrawRule.canDrawWholeLine(
        optimizeRender, exceed, hangingPunctuation, compressedPunctuation,
        onlyTextColumn, isMsgPage
    )

    @Test
    fun `a plain text line draws in one run`() {
        assertTrue(canDrawWholeLine())
    }

    @Test
    fun `a hanging punctuation line falls back to per column drawing`() {
        // 悬挂标点把首个标点左移到缩进内,整体绘制会把它排回字宽顺序上
        assertFalse(canDrawWholeLine(hangingPunctuation = true))
    }

    @Test
    fun `a compressed punctuation line falls back to per column drawing`() {
        // 挤压后的标点列窄于字宽,整体绘制仍按原字宽排字,其后的字会整体右移
        assertFalse(canDrawWholeLine(compressedPunctuation = true))
    }

    @Test
    fun `lines whose columns were moved or are not text fall back`() {
        assertFalse(canDrawWholeLine(exceed = true))
        assertFalse(canDrawWholeLine(onlyTextColumn = false))
        assertFalse(canDrawWholeLine(isMsgPage = true))
    }

    @Test
    fun `render optimization off always draws per column`() {
        assertFalse(canDrawWholeLine(optimizeRender = false))
        assertFalse(canDrawWholeLine(optimizeRender = false, hangingPunctuation = true))
    }
}
