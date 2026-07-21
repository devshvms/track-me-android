package `in`.shvms.trackme.domain.stats

/**
 * B1 post-ride reveal — a bounded, celebratory outcome shown once, right after a good ride is
 * saved. "Bounded" is the guardrail: a small fixed set of earned outcomes, never slot-machine
 * randomness (trust-eroding for a safety app — `user-psychology.md` §1).
 *
 * A [Reveal] is a pure *fact* derived from the A1 [RideStatsTransition]; it carries no Android /
 * UI / analytics types so it can be persisted (survive process death) and unit-tested. The
 * platform presenter maps [kind] + args to localized copy and the `reveal_type` telemetry value.
 */
data class Reveal(
    /** Stable ID = the ride ID, so a reveal is de-duplicated / acknowledged exactly once. */
    val rideId: Long,
    val kind: RevealKind,
    /** Total rides after this one (for "your Nth ride" / milestone copy). */
    val totalRides: Int,
    val distanceMeters: Double,
    val durationMillis: Long,
    /** Non-null only for [RevealKind.MILESTONE] — the count crossed (10, 25, 50, ...). */
    val milestoneRideCount: Int?
) {
    /**
     * Telemetry value for `post_ride_reveal_shown {reveal_type}`. MUST stay within the A1
     * taxonomy allow-list {"pr","first_ride","milestone","default"} and be identical on iOS.
     */
    val revealType: String
        get() = when (kind) {
            RevealKind.FIRST_RIDE -> "first_ride"
            RevealKind.DISTANCE_PR, RevealKind.DURATION_PR -> "pr"
            RevealKind.MILESTONE -> "milestone"
            RevealKind.DEFAULT -> "default"
        }
}

/**
 * The bounded reveal set. Ordered by presentation priority (see [RevealSelector]); the two PR
 * kinds collapse to the single `"pr"` telemetry type but keep distinct copy (distance vs time).
 */
enum class RevealKind {
    FIRST_RIDE,
    DISTANCE_PR,
    DURATION_PR,
    MILESTONE,
    DEFAULT
}
