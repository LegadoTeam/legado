package io.legado.app.help.book

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentReversalTest {
    @Test fun `plain text keeps Unicode code points and is reversible`() {
        val content = " \r\n甲😀e\u0301𠀀乙\n "
        assertEquals(" \n乙𠀀\u0301e😀甲\n\r ", reverseContentText(content))
        assertEquals(content, reverseContentText(reverseContentText(content)))
    }

    @Test fun `reader images and their nonstandard JSON stay byte for byte unchanged`() {
        val image = """<img src="https://example.com/image.png,{"style":"TEXT","reviewCount":"12","click":"getDP(1,12)","js":"dpurl(12,'正文')"}">"""
        val dataImage = """<img src="data:image/svg+xml;base64,PHN2Zy8+,{'click':'showCmt("1234567890123456789","49")','style':'text'}">"""
        assertEquals("乙甲${dataImage}丁丙", reverseContentText("甲乙${dataImage}丙丁"))
        val content = "甲😀乙$image 丙丁"
        assertEquals("乙😀甲${image}丁丙 ", reverseContentText(content))
        assertEquals(content, reverseContentText(reverseContentText(content)))
        val paragraphs = "甲乙$image\r\n丙丁$image\n"
        assertEquals("乙甲$image\r\n丁丙$image\n", reverseContentText(paragraphs))
        assertEquals(paragraphs, reverseContentText(reverseContentText(paragraphs)))
    }

    @Test fun `complete html scripts tags entities and newpage retain structure`() {
        val html = """<usehtml><b title="正文">甲&amp;乙</b>
<button>@onclick:java.toast('不能反转脚本')</button><img src="a,{"width":"50%"}"></usehtml>"""
        val content = "甲乙$html 丙丁[newpage]<b>戊己</b><!--正文-->"
        assertEquals("乙甲${html}丁丙 [newpage]<b>己戊</b><!--正文-->", reverseContentText(content))
        assertEquals(content, reverseContentText(reverseContentText(content)))
    }
}
