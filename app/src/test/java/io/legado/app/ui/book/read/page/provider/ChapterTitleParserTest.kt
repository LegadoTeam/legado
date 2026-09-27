package io.legado.app.ui.book.read.page.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterTitleParserTest {

    @Test
    fun splitsSupportedChapterPrefixes() {
        assertEquals("第一章" to "初入江湖踏雪行", split("第一章 初入江湖踏雪行"))
        assertEquals("第一卷 第十二章" to "风云际会", split("第一卷 第十二章 风云际会"))
        assertEquals("第二十回" to "剑指苍穹破万阵", split("第二十回 剑指苍穹破万阵"))
        assertEquals("番外一" to "旧时烟雨", split("番外一 旧时烟雨"))
        assertEquals("第一章" to "全角空格", split("第一章　全角空格"))
    }

    @Test
    fun keepsUnsupportedOrDisabledTitlesUnchanged() {
        assertNull(split("雨夜闲谈"))
        assertNull(split("第一章"))
        assertNull(ChapterTitleParser.split("第一章 标题", enabled = false, isVolume = false))
        assertNull(ChapterTitleParser.split("第一章 标题", enabled = true, isVolume = true))
        assertNull(split("第一章\n标题"))
    }

    private fun split(title: String) =
        ChapterTitleParser.split(title, enabled = true, isVolume = false)
}
