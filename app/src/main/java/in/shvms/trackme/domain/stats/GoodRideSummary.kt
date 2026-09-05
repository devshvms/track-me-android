package `in`.shvms.trackme.domain.stats

/**
 * Platform-neutral contract describing a ride that has just been saved as a "good ride"
 * (i.e. it passed the junk-ride check in [in.shvms.trackme.service.TrackingService]).
 *
 * This is the single input to the shared retention/telemetry hook (A1). Every retention
 * feature (B1 reveal, B2 recap, B3 streak, B4 review) consumes the [RideStatsTransition]
 * produced from this summary rather than adding its own save listener.
 *
 * IMPORTANT: [distanceMeters] and [durationMillis] must be the authoritative in-memory
 * values already computed during finalization. Do NOT recompute distance from points here.
 */
data class GoodRideSummary(
    val rideId: Long,
    /** Wall-clock instant the ride was finalized, epoch millis. */
    val finishedAtMillis: Long,
    /** Active (non-paused) ride duration in millis. */
    val durationMillis: Long,
    /** Filtered ride distance in meters (as persisted on the ride). */
    val distanceMeters: Double,
    /** Local first-run samples can be replayed/exported but never enter retention aggregates. */
    val isSample: Boolean = false
)
