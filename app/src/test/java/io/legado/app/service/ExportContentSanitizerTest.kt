package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportContentSanitizerTest {

    @Test
    fun `removes review images but keeps ordinary images and text`() {
        val body = """
            正文​一
            <img src="https://example.com/body.jpg">
            <img src="data:image/svg+xml;base64,QQ,{'style':'TEXT','reviewCount':'37','click':'getDP(1,37)'}">
            <img src="data:image/svg+xml;base64,QQ,{'style':'full','click':'getZP(3)'}">
            <img src="http://localhost/54,{'style':'TEXT','js':'dpurl(65,\'000000\')','click':'dpshow(1)'}">
            <img src="data:image/svg+xml;base64,QQ,{'style':'text','reviewCount':'37','click':'openImage()'}">
            <img src="data:image/svg+xml;base64,QQ,{'style':'text','reviewCount':'0','click':'getDP(1,37)'}">
        """.trimIndent()

        val sanitized = sanitizeExportContent(body)

        assertTrue(sanitized.contains("正文一"))
        assertTrue(sanitized.contains("body.jpg"))
        assertFalse(sanitized.contains("getDP(1,37)"))
        assertFalse(sanitized.contains("getZP(3)"))
        assertFalse(sanitized.contains("dpshow(1)"))
        assertFalse(sanitized.contains("reviewCount"))
        assertFalse(sanitized.contains("\u200B"))
    }

    @Test
    fun `keeps non-review svg and ordinary click image`() {
        val body = """
            <img src="data:image/svg+xml;base64,QQ">
            <img src="https://example.com/map.svg,{'style':'full','click':'openMap()'}">
            <img src="https://example.com/icon.svg,{'style':'text','click':'getDP(1)'}">
        """.trimIndent()

        val sanitized = sanitizeExportContent(body)

        assertEquals(body, sanitized)
    }

    @Test
    fun `accepts invisible controls that leak from cached html`() {
        val input = "a\u200B\u200C\u200D\u200E\u200F\uFEFFb"
        assertEquals("a\u200C\u200D\u200E\u200Fb", sanitizeExportContent(input))
    }

    @Test
    fun `removes remaining body images before txt formatting`() {
        assertEquals(
            "前文后文",
            removeExportImages("前文<img src=\"https://example.com/body.jpg\">后文")
        )
    }
}
