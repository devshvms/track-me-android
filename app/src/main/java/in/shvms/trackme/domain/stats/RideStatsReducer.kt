package `in`.shvms.trackme.domain.stats

import java.time.ZoneId

/**
 * Pure reducer at the heart of A1: `(oldStats, summary, zone) -> (newStats, transition)`.
 *
 * No Android, no I/O, no analytics — fully unit-testable. The store
 * ([in.shvms.trackme.data.local.RideStatsStore]) is the only place that persists the result
 * and emits telemetry; the reducer just computes.
 *
 * Guarantees:
 *  - Idempotent by ride ID: folding the same ride twice is a no-op for aggregates and for
 *    every downstream eligibility flag ([RideStatsTransition.alreadyProcessed] = true).
 *  - PR flags compare against the PRE-update snapshot (so a ride never "beats itself").
 *  - Weeks are Monday-anchored via [WeekKey]; streak counts consecutive active weeks.
 */
object RideStatsReducer {

    /** Total-ride-count milestones worth celebrating (first ride is handled separately). */
    val MILESTONES = listOf(10, 25, 50, 100, 250, 500, 1000)

    fun reduce(
        old: RideStats,
        summary: GoodRideSummary,
        zone: ZoneId
    ): Pair<RideStats, RideStatsTransition> {

        // --- Idempotency: already folded in -> no-op transition reflecting current state ---
        if (old.processedRideIds.contains(summary.rideId)) {
            val noOp = RideStatsTransition(
                rideId = summary.rideId,
                alreadyProcessed = true,
                isFirstRide = false,
                isDistancePR = false,
                isDurationPR = false,
                milestoneRideCount = null,
                totalRides = old.totalRides,
                distanceMeters = summary.distanceMeters,
                durationMillis = summary.durationMillis,
                weekKey = WeekKey.label(old.currentWeekStartEpochDay.takeIf { it != 0L }
                    ?: WeekKey.weekStartEpochDay(summary.finishedAtMillis, zone)),
                weekRideCount = old.currentWeekRideCount,
                weekDistanceMeters = old.currentWeekDistanceMeters,
                streakWeeks = old.streakWeeks,
                isFirstRideOfWeek = false,
                streakAdvanced = false,
                streakFroze = false
            )
            return old to noOp
        }

        val isFirstRide = old.totalRides == 0

        // --- Personal records vs the pre-update snapshot (strict >, never on first ride) ---
        val isDistancePR = !isFirstRide && summary.distanceMeters > old.longestDistanceMeters
        val isDurationPR = !isFirstRide && summary.durationMillis > old.longestDurationMillis

        val newTotalRides = old.totalRides + 1
        val milestoneRideCount = if (newTotalRides in MILESTONES) newTotalRides else null

        // --- Weekly aggregates (Monday-anchored) ---
        val weekStart = WeekKey.weekStartEpochDay(summary.finishedAtMillis, zone)
        val sameWeek = old.totalRides > 0 && old.currentWeekStartEpochDay == weekStart
        val isFirstRideOfWeek = !sameWeek

        val newWeekRideCount: Int
        val newWeekDistance: Double
        if (sameWeek) {
            newWeekRideCount = old.currentWeekRideCount + 1
            newWeekDistance = old.currentWeekDistanceMeters + summary.distanceMeters
        } else {
            newWeekRideCount = 1
            newWeekDistance = summary.distanceMeters
        }

        // --- Weekly streak (consecutive active weeks) with B3 single-miss forgiveness ---
        // Weeks are Monday epoch-days, so the gap between two active weeks is a multiple of 7:
        //   7  -> consecutive (extend);  14 -> exactly one week missed (auto-freeze if a token
        //   is available); >14 or backwards -> reset. A freeze token is consumed by a forgiven
        //   miss and refilled by any active week, so isolated misses are each forgiven but two
        //   misses in a row cannot both be. Loss is NEVER surfaced (telemetry-only `froze`).
        var newStreakWeeks = old.streakWeeks
        var newLastStreakWeekStart = old.lastStreakWeekStartEpochDay
        var newFreezeAvailable = old.freezeAvailable
        var streakAdvanced = false
        var streakFroze = false
        if (isFirstRideOfWeek) {
            val gapDays = weekStart - old.lastStreakWeekStartEpochDay
            when {
                // First ever active week.
                old.lastStreakWeekStartEpochDay == 0L -> {
                    newStreakWeeks = 1
                    newFreezeAvailable = true
                }
                // Consecutive week -> extend; an active week refills the freeze token.
                gapDays == 7L -> {
                    newStreakWeeks = old.streakWeeks + 1
                    newFreezeAvailable = true
                }
                // Exactly one missed week -> forgive if a token is available (consume it).
                gapDays == 14L && old.freezeAvailable -> {
                    newStreakWeeks = old.streakWeeks + 1
                    newFreezeAvailable = false
                    streakFroze = true
                }
                // Same anchor (defensive; shouldn't happen when isFirstRideOfWeek) -> keep.
                gapDays == 0L -> newStreakWeeks = old.streakWeeks
                // Two+ missed weeks, or an unforgiven single miss, or clock moved back -> reset.
                else -> {
                    newStreakWeeks = 1
                    newFreezeAvailable = true
                }
            }
            newLastStreakWeekStart = weekStart
            streakAdvanced = newStreakWeeks > old.streakWeeks
        }

        // --- Bounded idempotency history ---
        val newProcessed = (old.processedRideIds + summary.rideId)
            .takeLast(RideStats.MAX_PROCESSED_IDS)

        val newStats = old.copy(
            schemaVersion = RideStats.CURRENT_SCHEMA_VERSION,
            totalRides = newTotalRides,
            totalDistanceMeters = old.totalDistanceMeters + summary.distanceMeters,
            longestDistanceMeters = maxOf(old.longestDistanceMeters, summary.distanceMeters),
            longestDurationMillis = maxOf(old.longestDurationMillis, summary.durationMillis),
            lastRideFinishedAtMillis = summary.finishedAtMillis,
            currentWeekStartEpochDay = weekStart,
            currentWeekRideCount = newWeekRideCount,
            currentWeekDistanceMeters = newWeekDistance,
            streakWeeks = newStreakWeeks,
            lastStreakWeekStartEpochDay = newLastStreakWeekStart,
            freezeAvailable = newFreezeAvailable,
            processedRideIds = newProcessed
        )

        val transition = RideStatsTransition(
            rideId = summary.rideId,
            alreadyProcessed = false,
            isFirstRide = isFirstRide,
            isDistancePR = isDistancePR,
            isDurationPR = isDurationPR,
            milestoneRideCount = milestoneRideCount,
            totalRides = newTotalRides,
            distanceMeters = summary.distanceMeters,
            durationMillis = summary.durationMillis,
            weekKey = WeekKey.label(weekStart),
            weekRideCount = newWeekRideCount,
            weekDistanceMeters = newWeekDistance,
            streakWeeks = newStreakWeeks,
            isFirstRideOfWeek = isFirstRideOfWeek,
            streakAdvanced = streakAdvanced,
            streakFroze = streakFroze
        )

        return newStats to transition
    }
}
