package io.legado.app.help.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.regex.Pattern

class ContentProcessorTitleTest {

    @Test
    fun `multiline title length follows normalized paragraphs including separators`() {
        val paragraphs = processBookParagraphs(
            "  第一章  \r\n\r\n　副标题　\n\n 正文一 \n 正文二 ",
            includeTitle = true, titleLineCount = 3, paragraphIndent = "　　",
        )
        assertEquals(listOf("第一章", "　　副标题", "　　正文一", "　　正文二"), paragraphs.textList)
        assertEquals("第一章\n　　副标题\n".length, paragraphs.titleLength)
        val content = BookContent(false, paragraphs.textList, null, paragraphs.titleLength)
        assertEquals("　　正文一\n　　正文二", content.toString().substring(content.titleLength))
    }

    @Test
    fun `empty title and hidden title have no title coordinates`() {
        val emptyTitle = processBookParagraphs("  \n 正文 ", true, 1, "　　")
        assertEquals(listOf("正文"), emptyTitle.textList)
        assertEquals(0, emptyTitle.titleLength)
        val hiddenTitle = processBookParagraphs(" 正文 ", false, 0, "　　")
        assertEquals(listOf("　　正文"), hiddenTitle.textList)
        assertEquals(0, hiddenTitle.titleLength)
        val titleOnly = processBookParagraphs("第一章\n", true, 1, "　　")
        assertEquals("第一章".length, titleOnly.titleLength)
        val empty = processBookParagraphs("\n　\n", true, 1, "　　")
        assertEquals(emptyList<String>(), empty.textList)
        assertEquals(0, empty.titleLength)
    }

    @Test
    fun `only an exact title line is removed`() {
        fun removeTitle(content: String): String? {
            val matcher = sameTitleLineMatcher(
                content,
                Pattern.quote("测试书"),
                "第一章"
            )
            return if (matcher.find()) content.substring(matcher.end()) else null
        }

        assertEquals("正文", removeTitle("【测试书】第一章\u3000\r\n  正文"))
        assertEquals("正文", removeTitle("第一章\u00a0\n正文"))
        assertEquals("", removeTitle("第一章"))
        assertNull(removeTitle("第一章补充标题\n正文"))
        assertNull(removeTitle("第一章 补充标题\n正文"))
    }
}
