package io.legado.app.utils

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class CronSchedule private constructor(
    private val minutes: BooleanArray,
    private val hours: BooleanArray,
    private val daysOfMonth: BooleanArray,
    private val months: BooleanArray,
    private val daysOfWeek: BooleanArray,
    private val domStartsWithStar: Boolean,
    private val dowStartsWithStar: Boolean
) {

    fun nextTimeAfter(fromEpochMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): Long? {
        val threshold = Instant.ofEpochMilli(fromEpochMs)
            .atZone(zoneId)
            .withSecond(0)
            .withNano(0)
            .plusMinutes(1)
            .toInstant()
        var date = threshold.atZone(zoneId).toLocalDate()
        repeat(MAX_DAYS) {
            findFirstOnDate(date, threshold, zoneId)?.let {
                return it.toEpochMilli()
            }
            date = date.plusDays(1)
        }
        return null
    }

    private fun findFirstOnDate(date: LocalDate, threshold: Instant, zoneId: ZoneId): Instant? {
        if (!months[date.monthValue]) return null
        val domMatch = daysOfMonth[date.dayOfMonth]
        val dowMatch = daysOfWeek[date.dayOfWeek.value % 7]
        val dayMatches = if (domStartsWithStar || dowStartsWithStar) {
            domMatch && dowMatch
        } else {
            domMatch || dowMatch
        }
        if (!dayMatches) return null

        var first: Instant? = null
        for (hour in hours.indices) {
            if (!hours[hour]) continue
            for (minute in minutes.indices) {
                if (!minutes[minute]) continue
                val local = LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, hour, minute)
                for (offset in zoneId.rules.getValidOffsets(local)) {
                    val candidate = local.toInstant(offset)
                    val current = first
                    if (!candidate.isBefore(threshold) &&
                        (current == null || candidate.isBefore(current))
                    ) {
                        first = candidate
                    }
                }
            }
        }
        return first
    }

    companion object {
        private const val MAX_DAYS = 8 * 366

        fun parse(expression: String): CronSchedule? {
            val parts = expression.trim().split(Regex("\\s+"))
            if (parts.size != 5 || parts.any { it.isBlank() }) return null
            val minute = parseField(parts[0], 0, 59) ?: return null
            val hour = parseField(parts[1], 0, 23) ?: return null
            val dayOfMonth = parseField(parts[2], 1, 31) ?: return null
            val month = parseField(parts[3], 1, 12) ?: return null
            val dayOfWeek = parseField(parts[4], 0, 7, mapSundayToZero = true)
                ?: return null
            return CronSchedule(
                minute.allowed,
                hour.allowed,
                dayOfMonth.allowed,
                month.allowed,
                dayOfWeek.allowed,
                dayOfMonth.startsWithStar,
                dayOfWeek.startsWithStar
            )
        }

        private data class Field(val allowed: BooleanArray, val startsWithStar: Boolean)

        private fun parseField(
            text: String,
            min: Int,
            max: Int,
            mapSundayToZero: Boolean = false
        ): Field? {
            if (text.isBlank() || text.contains('?')) return null
            val allowed = BooleanArray(max + 1)
            for (segment in text.split(',')) {
                if (segment.isBlank()) return null
                val stepParts = segment.split('/')
                if (stepParts.size > 2) return null
                val step = if (stepParts.size == 1) {
                    1
                } else {
                    stepParts[1].toIntOrNull() ?: return null
                }
                if (step <= 0) return null
                val base = stepParts[0]
                val range = when {
                    base == "*" -> min..max
                    base.count { it == '-' } == 1 -> {
                        val values = base.split('-')
                        val start = values[0].toIntOrNull() ?: return null
                        val end = values[1].toIntOrNull() ?: return null
                        if (start !in min..max || end !in min..max || start > end) return null
                        start..end
                    }
                    base.contains('-') -> return null
                    else -> {
                        val value = base.toIntOrNull() ?: return null
                        if (value !in min..max) return null
                        if (stepParts.size == 2) value..max else value..value
                    }
                }
                for (rawValue in range step step) {
                    val value = if (mapSundayToZero && rawValue == 7) 0 else rawValue
                    allowed[value] = true
                }
            }
            if (allowed.none { it }) return null
            return Field(allowed, text.startsWith('*'))
        }
    }
}
