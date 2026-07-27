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
    fun titleAndLaterLinesAreCallerResponsibility() {
        // shouldHang 只做文本判断,标题/非首行的排除由布局层完成
        assertTrue(HangingPunctuationRule.shouldHang("${indent}“abc", indent))
    }
}
