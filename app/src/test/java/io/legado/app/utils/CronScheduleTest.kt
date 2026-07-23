package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.system.measureTimeMillis

class CronScheduleTest {

    private val utc = ZoneId.of("UTC")

    @Test
    fun parsesListsRangesAndSteps() {
        val schedule = CronSchedule.parse("5/10 8-10 1,15 * 1-5")
        assertNotNull(schedule)
        val from = time(2026, 6, 1, 8, 4)
        assertEquals(time(2026, 6, 1, 8, 5), schedule!!.nextTimeAfter(from, utc))
        assertEquals(time(2026, 6, 1, 8, 15), schedule.nextTimeAfter(time(2026, 6, 1, 8, 5), utc))
    }

    @Test
    fun rejectsNonStandardOrOutOfRangeFields() {
        assertNull(CronSchedule.parse("0 0 * * MON"))
        assertNull(CronSchedule.parse("0 0 ? * 1"))
        assertNull(CronSchedule.parse("0 0 * * * extra"))
        assertNull(CronSchedule.parse("60 0 * * *"))
        assertNull(CronSchedule.parse("*/0 * * * *"))
        assertNull(CronSchedule.parse("*/x * * * *"))
        assertNull(CronSchedule.parse("*/ * * * *"))
    }

    @Test
    fun dayOfMonthAndDayOfWeekUseCronOrSemantics() {
        val schedule = CronSchedule.parse("0 9 13 * 1")!!
        assertEquals(
            time(2026, 6, 13, 9, 0),
            schedule.nextTimeAfter(time(2026, 6, 9, 9, 1), utc)
        )
        assertEquals(
            time(2026, 6, 15, 9, 0),
            schedule.nextTimeAfter(time(2026, 6, 13, 9, 0), utc)
        )
    }

    @Test
    fun starStepDayFieldsKeepWildcardSemantics() {
        val dayOfMonthStep = CronSchedule.parse("0 9 */2 * 1")!!
        assertEquals(
            time(2026, 6, 15, 9, 0),
            dayOfMonthStep.nextTimeAfter(time(2026, 6, 1, 9, 0), utc)
        )

        val dayOfWeekStep = CronSchedule.parse("0 9 13 * */2")!!
        assertEquals(
            time(2026, 8, 13, 9, 0),
            dayOfWeekStep.nextTimeAfter(time(2026, 7, 1, 9, 0), utc)
        )
    }

    @Test
    fun explicitFullDayOfMonthRangeIsNotAWildcard() {
        val schedule = CronSchedule.parse("0 9 1-31 * 1")!!
        assertEquals(
            time(2026, 6, 16, 9, 0),
            schedule.nextTimeAfter(time(2026, 6, 15, 9, 0), utc)
        )
    }

    @Test
    fun searchesFarEnoughToReachLeapDay() {
        val schedule = CronSchedule.parse("0 0 29 2 *")!!
        val from = time(2025, 3, 1, 0, 0)
        var next: Long? = null
        val elapsed = measureTimeMillis {
            next = schedule.nextTimeAfter(from, utc)
        }
        assertEquals(time(2028, 2, 29, 0, 0), next)
        assertTrue(next!! - from > 366L * 24 * 60 * 60 * 1000)
        assertTrue("leap-day lookup took ${elapsed}ms", elapsed < 5_000)
    }

    @Test
    fun impossibleDateStopsWithinBoundedSearchWindow() {
        val schedule = CronSchedule.parse("0 0 31 2 *")!!
        var next: Long? = 0L
        val elapsed = measureTimeMillis {
            next = schedule.nextTimeAfter(time(2026, 1, 1, 0, 0), utc)
        }
        assertNull(next)
        assertTrue("invalid-date lookup took ${elapsed}ms", elapsed < 5_000)
    }

    @Test
    fun acceptsSevenAsSunday() {
        val schedule = CronSchedule.parse("0 12 * * 7")!!
        assertEquals(
            time(2026, 6, 14, 12, 0),
            schedule.nextTimeAfter(time(2026, 6, 13, 12, 0), utc)
        )
    }

    private fun time(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, utc)
            .toInstant()
            .toEpochMilli()
    }
}
