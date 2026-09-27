package io.legado.app.ui.replace.edit

import io.legado.app.data.entities.ReplaceRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.TimeUnit

class ReplacePreviewTest {

    @Test
    fun `sample input is capped at three hundred characters`() {
        val sample = "x".repeat(ReplacePreview.MAX_SAMPLE_LENGTH + 1)

        assertEquals(ReplacePreview.MAX_SAMPLE_LENGTH, ReplacePreview.normalizeSample(sample).length)
    }

    @Test
    fun `sample limit does not leave a dangling unicode surrogate`() {
        val sample = "x".repeat(ReplacePreview.MAX_SAMPLE_LENGTH - 1) + "😀"

        assertFalse(ReplacePreview.normalizeSample(sample).last().isHighSurrogate())
    }

    @Test
    fun `preview applies one literal replacement rule`() {
        val rule = ReplaceRule(
            pattern = ".",
            replacement = "",
            isRegex = false
        )

        assertEquals("123测试文本", preview(rule, "1.2.3.测试.文本"))
    }

    @Test
    fun `preview matches the reported escaped-dot regex example`() {
        val rule = ReplaceRule(
            pattern = "\\.",
            replacement = "",
            isRegex = true
        )

        assertEquals("123测试文本", preview(rule, "1.2.3.测试.文本"))
    }

    @Test
    fun `preview applies one regular expression replacement rule`() {
        val rule = ReplaceRule(
            pattern = "\\d+",
            replacement = "#",
            isRegex = true
        )

        assertEquals("a#b#", preview(rule, "a12b34"))
    }

    @Test
    fun `preview preserves js replacement semantics`() {
        val rule = ReplaceRule(
            name = "uppercase",
            pattern = "[a-z]+",
            replacement = "@js:result.toUpperCase()",
            isRegex = true
        )

        assertEquals("A 123 B", preview(rule, "a 123 b"))
    }

    @Test
    fun `preview reports missing book context for context dependent js`() {
        val rule = ReplaceRule(
            pattern = ".",
            replacement = "@js:book.name",
            isRegex = true
        )

        val error = assertThrows(ReplacePreviewException::class.java) {
            preview(rule, "x")
        }

        assertEquals(ReplacePreviewException.Reason.CONTEXT_UNAVAILABLE, error.reason)
    }

    @Test
    fun `preview reports missing context for non property book references`() {
        val rule = ReplaceRule(
            pattern = ".",
            replacement = "@js:book == null ? result : ''",
            isRegex = true
        )

        val error = assertThrows(ReplacePreviewException::class.java) {
            preview(rule, "x")
        }

        assertEquals(ReplacePreviewException.Reason.CONTEXT_UNAVAILABLE, error.reason)
    }

    @Test
    fun `preview allows book as a js string literal`() {
        val rule = ReplaceRule(
            pattern = ".",
            replacement = "@js:'book'",
            isRegex = true
        )

        assertEquals("book", preview(rule, "x"))
    }

    @Test
    fun `preview stops an infinite js replacement at the rule timeout`() {
        val rule = ReplaceRule(
            pattern = ".",
            replacement = "@js:while (true) {}",
            isRegex = true,
            timeoutMillisecond = 25
        )

        val error = assertThrows(ReplacePreviewException::class.java) {
            preview(rule, "x")
        }

        assertEquals(ReplacePreviewException.Reason.TIMEOUT, error.reason)
    }

    @Test
    fun `preview stops regex work after the rule deadline`() {
        val rule = ReplaceRule(
            pattern = ".",
            replacement = "",
            isRegex = true,
            timeoutMillisecond = 25
        )
        var now = 0L
        val clockStep = TimeUnit.MILLISECONDS.toNanos(rule.timeoutMillisecond + 1)

        val error = assertThrows(ReplacePreviewException::class.java) {
            runBlocking {
                ReplacePreview.apply(rule, "x") {
                    now += clockStep
                    now
                }
            }
        }

        assertEquals(ReplacePreviewException.Reason.TIMEOUT, error.reason)
    }

    private fun preview(rule: ReplaceRule, sample: String): String = runBlocking {
        ReplacePreview.apply(rule, sample)
    }
}
