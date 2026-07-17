package io.legado.app.help.config

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderInfoTemplateTest {

    private val values = ReaderInfoValues(
        bookName = "Book",
        chapterTitle = "Chapter",
        time = "12:34",
        battery = 85,
        page = "2",
        totalPages = "10",
        readProgress = "35.2%",
        chapter = "3",
        totalChapters = "20",
    )

    @Test
    fun `parse mixed literal text placeholders and battery icon`() {
        assertEquals(
            listOf(
                ReaderInfoPart.Text("Book · 12:34 "),
                ReaderInfoPart.BatteryIcon(85),
                ReaderInfoPart.Text(" 85%"),
            ),
            ReaderInfoTemplate.parse("{书名} · {时间} {电量图标} {电量}", values),
        )
    }

    @Test
    fun `parse every approved text placeholder`() {
        assertEquals(
            listOf(ReaderInfoPart.Text("Book|Chapter|12:34|85%|2|10|35.2%|3|20")),
            ReaderInfoTemplate.parse(
                "{书名}|{章节}|{时间}|{电量}|{页码}|{总页数}|{阅读进度}|{章节序号}|{章节总数}",
                values,
            ),
        )
    }

    @Test
    fun `unknown and malformed placeholders remain literal`() {
        assertEquals(
            listOf(ReaderInfoPart.Text("{未知} {书名")),
            ReaderInfoTemplate.parse("{未知} {书名", values),
        )
    }

    @Test
    fun `doubled braces remain literal`() {
        assertEquals(
            listOf(ReaderInfoPart.Text("{{书名}}")),
            ReaderInfoTemplate.parse("{{书名}}", values),
        )
    }

    @Test
    fun `nested braces remain literal`() {
        assertEquals(
            listOf(ReaderInfoPart.Text("{outer{章节}}")),
            ReaderInfoTemplate.parse("{outer{章节}}", values),
        )
    }

    @Test
    fun `balanced nested segment with outer tail remains literal`() {
        assertEquals(
            listOf(ReaderInfoPart.Text("{outer{章节}tail}")),
            ReaderInfoTemplate.parse("{outer{章节}tail}", values),
        )
    }

    @Test
    fun `balanced segment with multiple nested tokens remains literal`() {
        assertEquals(
            listOf(ReaderInfoPart.Text("{left{章节}middle{时间}right}")),
            ReaderInfoTemplate.parse("{left{章节}middle{时间}right}", values),
        )
    }

    @Test
    fun `unbalanced braces remain literal`() {
        assertEquals(
            listOf(ReaderInfoPart.Text("{书名 | 书名")),
            ReaderInfoTemplate.parse("{书名 | 书名", values),
        )
    }

    @Test
    fun `scanner resumes at valid placeholder after malformed expression`() {
        assertEquals(
            listOf(ReaderInfoPart.Text("{broken 12:34")),
            ReaderInfoTemplate.parse("{broken {时间}", values),
        )
    }

    @Test
    fun `empty template has no parts`() {
        assertEquals(emptyList<ReaderInfoPart>(), ReaderInfoTemplate.parse("", values))
    }

    @Test
    fun `battery values are clamped`() {
        assertEquals(
            listOf(ReaderInfoPart.BatteryIcon(100), ReaderInfoPart.Text(" 100%")),
            ReaderInfoTemplate.parse("{电量图标} {电量}", values.copy(battery = 120)),
        )
        assertEquals(
            listOf(ReaderInfoPart.BatteryIcon(0), ReaderInfoPart.Text(" 0%")),
            ReaderInfoTemplate.parse("{电量图标} {电量}", values.copy(battery = -1)),
        )
    }

    @Test
    fun `legacy values map to equivalent templates`() {
        val expected = mapOf(
            ReadTipConfig.none to "",
            ReadTipConfig.chapterTitle to "{章节}",
            ReadTipConfig.time to "{时间}",
            ReadTipConfig.battery to "{电量图标}",
            ReadTipConfig.batteryPercentage to "{电量}",
            ReadTipConfig.page to "{页码}/{总页数}",
            ReadTipConfig.totalProgress to "{阅读进度}",
            ReadTipConfig.pageAndTotal to "{页码}/{总页数}  {阅读进度}",
            ReadTipConfig.bookName to "{书名}",
            ReadTipConfig.timeBattery to "{时间}  {电量图标}",
            ReadTipConfig.timeBatteryPercentage to "{时间} {电量}",
            ReadTipConfig.totalProgress1 to "{章节序号}/{章节总数}",
        )

        expected.forEach { (legacy, template) ->
            assertEquals(template, ReaderInfoTemplate.fromLegacy(legacy))
        }
        assertEquals("", ReaderInfoTemplate.fromLegacy(Int.MAX_VALUE))
    }

    @Test
    fun `explicit template takes precedence over legacy value`() {
        assertEquals("", ReadTipConfig.effectiveTemplate("", ReadTipConfig.bookName))
        assertEquals("custom", ReadTipConfig.effectiveTemplate("custom", ReadTipConfig.bookName))
        assertEquals("{书名}", ReadTipConfig.effectiveTemplate(null, ReadTipConfig.bookName))
    }
}
