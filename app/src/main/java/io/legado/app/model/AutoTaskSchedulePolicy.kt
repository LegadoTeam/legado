package io.legado.app.model

import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.utils.CronSchedule
import java.time.ZoneId

object AutoTaskSchedulePolicy {

    const val FIRST_RUN_GRACE_MS = 5 * 60_000L

    fun dueRules(
        rules: List<AutoTaskRule>,
        now: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<AutoTaskRule> {
        return rules.filter { rule ->
            if (!rule.enable) return@filter false
            val schedule = CronSchedule.parse(rule.cron.orEmpty()) ?: return@filter false
            schedule.nextTimeAfter(baseTime(rule, now), zoneId)?.let { it <= now } == true
        }
    }

    fun nextDueAt(
        rules: List<AutoTaskRule>,
        now: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long? {
        return rules.asSequence()
            .filter { it.enable }
            .mapNotNull { rule ->
                CronSchedule.parse(rule.cron.orEmpty())
                    ?.nextTimeAfter(baseTime(rule, now), zoneId)
                    ?.coerceAtLeast(now)
            }
            .minOrNull()
    }

    fun nextAfterBatchAt(
        rules: List<AutoTaskRule>,
        now: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long? {
        return rules.asSequence()
            .filter { it.enable }
            .mapNotNull { CronSchedule.parse(it.cron.orEmpty())?.nextTimeAfter(now, zoneId) }
            .minOrNull()
    }

    fun alternateSlot(currentJobId: Int, firstJobId: Int, secondJobId: Int): Int {
        return if (currentJobId == firstJobId) secondJobId else firstJobId
    }

    private fun baseTime(rule: AutoTaskRule, now: Long): Long {
        return rule.lastRunAt.takeIf { it > 0L } ?: (now - FIRST_RUN_GRACE_MS)
    }
}
