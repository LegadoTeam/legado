package io.legado.app.help.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.regex.Pattern

class ContentProcessorTitleTest {

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
