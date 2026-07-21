package `in`.shvms.trackme.domain.stats

import java.time.ZoneId

/**
 * Pure B2 decision: given the current [RideStats] and "now", is there a completed week worth
 * recapping? No Android, no persistence — fully unit-testable, mirroring [RevealSelector].
 *
 * Rules (gain-framed, never nag):
 *  - The completed week is the last active week ([RideStats.currentWeekStartEpochDay]) once
 *    "now" has moved into a strictly later Monday-anchored week.
 *  - Zero-ride weeks produce nothing (silence, not a loss message).
 *  - Already-acknowledged weeks produce nothing (dedupe via [RideStats.lastRecapShownWeekStartEpochDay]).
 *
 * This is a read-only decision; acknowledgement (marking it shown) is a separate store mutation
 * so the recap is only dedup'd after it was actually presented.
 */
object WeeklyRecapSelector {

    fun select(stats: RideStats, nowMillis: Long, zone: ZoneId): WeeklyRecap? {
        val thisWeekStart = WeekKey.weekStartEpochDay(nowMillis, zone)
        if (stats.currentWeekStartEpochDay == 0L) return null            // never rode
        if (stats.currentWeekStartEpochDay >= thisWeekStart) return null // still the active week
        if (stats.currentWeekRideCount <= 0) return null                 // nothing to celebrate
        if (stats.lastRecapShownWeekStartEpochDay == stats.currentWeekStartEpochDay) return null
        return WeeklyRecap(
            weekKey = WeekKey.label(stats.currentWeekStartEpochDay),
            weekStartEpochDay = stats.currentWeekStartEpochDay,
            rideCount = stats.currentWeekRideCount,
            distanceMeters = stats.currentWeekDistanceMeters,
            streakWeeks = stats.streakWeeks
        )
    }
}
