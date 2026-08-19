package io.legado.app.ui.about

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadRecordDurationTest {

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
}
