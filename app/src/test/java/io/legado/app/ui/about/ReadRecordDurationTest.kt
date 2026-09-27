package io.legado.app.ui.about

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadRecordDurationTest {

    @Test
    fun `hiding seconds keeps minute precision and independent day choice`() {
        assertEquals("25小时2分钟", formatDuring((25 * 3600 + 123) * 1000L, showSeconds = false))
        assertEquals("1天1小时2分钟", formatDuring((25 * 3600 + 123) * 1000L, true, false))
        assertEquals("0分钟", formatDuring(59_999L, showSeconds = false))
        assertEquals("0分钟", formatDuring(0L, showSeconds = false))
        assertEquals("1分钟", formatDuring(60_000L, showSeconds = false))
    }

    @Test
    fun `durations over one day keep total hours`() {
        assertEquals("24小时", formatDuring(24 * 60 * 60 * 1000L))
        assertEquals(
            "25小时2分钟3秒",
            formatDuring((25 * 60 * 60 + 2 * 60 + 3) * 1000L)
        )
    }

    @Test
    fun `short and empty durations keep existing units`() {
        assertEquals("59秒", formatDuring(59_000L))
        assertEquals("0秒", formatDuring(0L))
    }

    @Test
    fun `optional days retain hours minutes and seconds`() {
        assertEquals("1天", formatDuring(24 * 60 * 60 * 1000L, true))
        assertEquals("2天1小时2分钟3秒", formatDuring((49 * 3600 + 123) * 1000L, true))
        assertEquals("59秒", formatDuring(59_000L, true))
        assertEquals("0秒", formatDuring(0L, true))
    }
}
