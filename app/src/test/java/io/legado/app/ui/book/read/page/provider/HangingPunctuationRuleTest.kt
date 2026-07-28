package io.legado.app.ui.book.read.page.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HangingPunctuationRuleTest {

    private val indent = "　　"

    @Test
    fun hangsOpeningQuotesAfterIndent() {
        listOf('“', '‘', '「', '『', '﹁', '﹃', '"', '\'').forEach { quote ->
            assertTrue(
                "expect hang for $quote",
                HangingPunctuationRule.shouldHang("${indent}${quote}你好。", indent)
            )
        }
    }

    @Test
    fun ordinaryFirstCharDoesNotHang() {
        assertFalse(HangingPunctuationRule.shouldHang("${indent}你好。", indent))
    }

    @Test
    fun closingOrMiddlePunctuationDoesNotHang() {
        assertFalse(HangingPunctuationRule.shouldHang("${indent}”你好。", indent))
        assertFalse(HangingPunctuationRule.shouldHang("${indent}，你好。", indent))
        assertFalse(HangingPunctuationRule.shouldHang("${indent}（你好）", indent))
    }

    @Test
    fun requiresConfiguredIndent() {
        assertFalse(HangingPunctuationRule.shouldHang("“你好。", ""))
    }

    @Test
    fun requiresTextStartingWithIndent() {
        assertFalse(HangingPunctuationRule.shouldHang("“你好。", indent))
        assertFalse(HangingPunctuationRule.shouldHang("　“你好。", indent))
    }

    @Test
    fun requiresContentBeyondIndent() {
        assertFalse(HangingPunctuationRule.shouldHang(indent, indent))
    }

    @Test
    fun rightToLeftParagraphsDoNotHang() {
        // 排版逐字从左往右累加坐标,右向段落的引号悬挂会落在错误的一边
        assertFalse(HangingPunctuationRule.shouldHang("${indent}\"مرحبا", indent))
        assertFalse(HangingPunctuationRule.shouldHang("${indent}\"שלום", indent))
        assertFalse(HangingPunctuationRule.shouldHang("${indent}“مرحبا”", indent))
    }

    @Test
    fun neutralCharactersDoNotDecideDirection() {
        // 数字/标点/空白不是强方向字符,要继续往后找
        assertTrue(HangingPunctuationRule.shouldHang("${indent}\"123 hello", indent))
        assertFalse(HangingPunctuationRule.shouldHang("${indent}\"123 مرحبا", indent))
        // 通篇没有强方向字符时按左向处理
        assertTrue(HangingPunctuationRule.shouldHang("${indent}\"123", indent))
    }

    @Test
    fun leftToRightParagraphsStillHang() {
        assertTrue(HangingPunctuationRule.shouldHang("${indent}“你好。", indent))
        assertTrue(HangingPunctuationRule.shouldHang("${indent}\"hello", indent))
    }

    @Test
    fun titleAndLaterLinesAreCallerResponsibility() {
        // shouldHang 只做文本判断,标题/非首行的排除由布局层完成
        assertTrue(HangingPunctuationRule.shouldHang("${indent}“abc", indent))
    }
}
