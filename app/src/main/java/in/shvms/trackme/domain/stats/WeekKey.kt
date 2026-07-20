package `in`.shvms.trackme.domain.stats

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields

/**
 * Single source of truth for Monday-anchored week boundaries (A1 guardrail: "week key must
 * be a single helper" so B2/B3 never drift). Injects the [ZoneId] so tests can pin a zone
 * and exercise DST / timezone boundaries deterministically.
 *
 * Uses java.time via core-library desugaring (min SDK 24, desugaring enabled).
 */
object WeekKey {

    /** Epoch-day of the Monday of the week containing [epochMillis] in [zone]. */
    fun weekStartEpochDay(epochMillis: Long, zone: ZoneId): Long {
        val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay()
    }

    /** ISO-8601 week label ("YYYY-Www") for a Monday epoch-day. */
    fun label(weekStartEpochDay: Long): String {
        val monday = LocalDate.ofEpochDay(weekStartEpochDay)
        val week = monday.get(WeekFields.ISO.weekOfWeekBasedYear())
        val year = monday.get(WeekFields.ISO.weekBasedYear())
        return "%04d-W%02d".format(year, week)
    }

    /** Convenience: ISO week label directly from an instant + zone. */
    fun label(epochMillis: Long, zone: ZoneId): String =
        label(weekStartEpochDay(epochMillis, zone))
}
