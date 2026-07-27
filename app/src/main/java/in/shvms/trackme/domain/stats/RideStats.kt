package `in`.shvms.trackme.domain.stats

/**
 * Versioned local aggregate of ride history used by all v1.6.0 retention features.
 *
 * Design rules (A1):
 *  - Purely additive schema. A new field in a future version MUST default here so an old
 *    persisted blob still deserializes without crashing (fail-open on missing fields).
 *  - Persisted as ONE versioned record (see [in.shvms.trackme.data.local.RideStatsStore]),
 *    never many independently-written keys.
 *  - This class is pure Kotlin (no Android imports) so the reducer is unit-testable.
 *
 * Weeks are Monday-anchored. We store each week as the epoch-day of its Monday
 * ([currentWeekStartEpochDay] / [lastStreakWeekStartEpochDay]); because every value is a
 * Monday, consecutive weeks differ by exactly 7, which makes streak arithmetic trivial.
 */
data class RideStats(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val totalRides: Int = 0,
    val totalDistanceMeters: Double = 0.0,
    /** Longest single-ride distance seen so far (distance personal record). */
    val longestDistanceMeters: Double = 0.0,
    /** Longest single-ride active duration seen so far (duration personal record). */
    val longestDurationMillis: Long = 0L,
    val lastRideFinishedAtMillis: Long = 0L,
    val currentWeekStartEpochDay: Long = 0L,
    val currentWeekRideCount: Int = 0,
    val currentWeekDistanceMeters: Double = 0.0,
    /** Number of consecutive active weeks (weekly, not daily). */
    val streakWeeks: Int = 0,
    /** Monday epoch-day of the most recent week that counted toward the streak. */
    val lastStreakWeekStartEpochDay: Long = 0L,
    /**
     * B3 streak forgiveness: whether a single-week miss can currently be auto-frozen without
     * breaking the streak. Consumed when a lone miss is forgiven; refilled after any active
     * week. `true` for a fresh store so the first isolated miss is always forgiven.
     */
    val freezeAvailable: Boolean = true,
    /**
     * B2 dedupe: Monday epoch-day of the completed week whose recap has already been surfaced,
     * so the weekly recap shows at most once per week even across many app opens.
     */
    val lastRecapShownWeekStartEpochDay: Long = 0L,
    /** Bounded set of ride IDs already folded in, for idempotent finalization/retry. */
    val processedRideIds: List<Long> = emptyList()
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        /** Cap on the idempotency history so the persisted blob stays small. */
        const val MAX_PROCESSED_IDS = 200
    }
}

/**
 * Pure result of folding one [GoodRideSummary] into the stats. Carries only *facts*;
 * downstream features map facts to UI/telemetry (e.g. B1's RevealSelector), keeping the
 * reducer free of presentation or analytics concerns.
 */
data class RideStatsTransition(
    val rideId: Long,
    /** True when this ride ID was already folded in — callers must treat as a no-op. */
    val alreadyProcessed: Boolean,
    val isFirstRide: Boolean,
    /** Strict distance PR against the pre-update snapshot (never true on the first ride). */
    val isDistancePR: Boolean,
    /** Strict duration PR against the pre-update snapshot (never true on the first ride). */
    val isDurationPR: Boolean,
    /** Non-null when this ride crossed a total-ride-count milestone (10, 25, 50, ...). */
    val milestoneRideCount: Int?,
    val totalRides: Int,
    val distanceMeters: Double,
    val durationMillis: Long,
    /** ISO-8601 Monday-anchored week label, e.g. "2026-W30" (for display + telemetry). */
    val weekKey: String,
    val weekRideCount: Int,
    val weekDistanceMeters: Double,
    val streakWeeks: Int,
    /** True when this ride is the first good ride of its (Monday-anchored) week. */
    val isFirstRideOfWeek: Boolean,
    /** True when this ride advanced the streak counter above its previous value. */
    val streakAdvanced: Boolean,
    /** True when this ride's week-rollover forgave a single missed week (B3 auto-freeze). */
    val streakFroze: Boolean,
    /** True when B1/B4 post-ride celebrations must not be presented for this ride. */
    val suppressPostRideCelebrations: Boolean = false
)

/**
 * B2 immutable snapshot of a completed (rolled-over) week, ready to present in the recap card.
 * Gain-framed facts only; the presenter formats + localizes. [streakWeeks] is the B3 line.
 */
data class WeeklyRecap(
    /** ISO-8601 label of the completed week, e.g. "2026-W29". */
    val weekKey: String,
    /** Monday epoch-day of the completed week (used to acknowledge/dedupe). */
    val weekStartEpochDay: Long,
    val rideCount: Int,
    val distanceMeters: Double,
    /** Consecutive active weeks as of this completed week (B3 streak line; never loss-framed). */
    val streakWeeks: Int
)
