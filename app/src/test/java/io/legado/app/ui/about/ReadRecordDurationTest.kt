package io.legado.app.ui.about

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadRecordDurationTest {

    @Test
    fun `durations over one day keep total hours`() {
        val activity = ReadRecordActivity()
        assertEquals("24小时", activity.formatDuring(24 * 60 * 60 * 1000L))
        assertEquals(
            "25小时2分钟3秒",
            activity.formatDuring((25 * 60 * 60 + 2 * 60 + 3) * 1000L)
        )
    }

    @Test
    fun `short and empty durations keep existing units`() {
        val activity = ReadRecordActivity()
        assertEquals("59秒", activity.formatDuring(59_000L))
        assertEquals("0秒", activity.formatDuring(0L))
    }
}
