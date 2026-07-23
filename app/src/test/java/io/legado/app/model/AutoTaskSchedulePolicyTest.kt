package io.legado.app.model

import io.legado.app.data.entities.AutoTaskRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AutoTaskSchedulePolicyTest {

    private val utc = ZoneId.of("UTC")

    @Test
    fun newTaskRunsOnceWhenRecentCronPointIsInsideGraceWindow() {
        val now = time(2026, 6, 1, 12, 3)
        val task = task("*/5 * * * *")
        assertEquals(listOf(task.id), AutoTaskSchedulePolicy.dueRules(listOf(task), now, utc).map { it.id })
        assertEquals(now, AutoTaskSchedulePolicy.nextDueAt(listOf(task), now, utc))
    }

    @Test
    fun newTaskWaitsForNextCronPointOutsideGraceWindow() {
        val now = time(2026, 6, 1, 12, 10)
        val task = task("0 * * * *")
        assertEquals(emptyList<String>(), AutoTaskSchedulePolicy.dueRules(listOf(task), now, utc).map { it.id })
        assertEquals(time(2026, 6, 1, 13, 0), AutoTaskSchedulePolicy.nextDueAt(listOf(task), now, utc))
    }

    @Test
    fun ignoresDisabledAndInvalidTasks() {
        val now = time(2026, 6, 1, 12, 3)
        val disabled = task("* * * * *").copy(enable = false)
        val invalid = task("not cron")
        assertNull(AutoTaskSchedulePolicy.nextDueAt(listOf(disabled, invalid), now, utc))
        assertEquals(emptyList<String>(), AutoTaskSchedulePolicy.dueRules(listOf(disabled, invalid), now, utc).map { it.id })
    }

    @Test
    fun schedulesFuturePointBeforeProcessingCurrentBatch() {
        val now = time(2026, 6, 1, 12, 3)
        val task = task("*/5 * * * *")
        assertEquals(time(2026, 6, 1, 12, 5), AutoTaskSchedulePolicy.nextAfterBatchAt(listOf(task), now, utc))
    }

    @Test
    fun impossibleScheduleHasNoFutureBatch() {
        val now = time(2026, 6, 1, 12, 3)
        assertNull(
            AutoTaskSchedulePolicy.nextAfterBatchAt(
                listOf(task("0 0 31 2 *")),
                now,
                utc
            )
        )
    }

    @Test
    fun alternatesPersistedJobSlots() {
        assertEquals(2002, AutoTaskSchedulePolicy.alternateSlot(2001, 2001, 2002))
        assertEquals(2001, AutoTaskSchedulePolicy.alternateSlot(2002, 2001, 2002))
    }

    private fun task(cron: String): AutoTaskRule {
        return AutoTaskRule(name = cron, cron = cron, script = "1")
    }

    private fun time(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, utc)
            .toInstant()
            .toEpochMilli()
    }
}
