package `in`.shvms.trackme.domain.stats

/**
 * Pure B1 decision: `RideStatsTransition -> Reveal?`. This is the *only* place that decides
 * which bounded outcome a saved ride earns, so the priority is defined once and is testable
 * without Android, persistence, or analytics.
 *
 * Priority (highest first, per the B1 spec "first ride → distance/duration PR → milestone →
 * default"). A single strict winner is chosen so two outcomes never compete:
 *
 *  1. [RevealKind.FIRST_RIDE]  — no history to beat; celebrate the start, never an empty PR.
 *  2. [RevealKind.DISTANCE_PR] — strict distance record vs the pre-update snapshot.
 *  3. [RevealKind.DURATION_PR] — strict duration record vs the pre-update snapshot.
 *  4. [RevealKind.MILESTONE]   — crossed a total-ride-count milestone (10, 25, ...).
 *  5. [RevealKind.DEFAULT]     — an ordinary good ride still gets a warm confirmation
 *                                (replaces the flat "Ride saved" toast).
 *
 * Returns null only when there is nothing to show: an already-processed (idempotent replay)
 * ride. Junk rides never reach here — the A1 hook excludes them upstream.
 */
object RevealSelector {

    fun select(t: RideStatsTransition): Reveal? {
        // An SOS ride is still part of the user's history, but it must not produce a celebratory
        // reveal or chain into the Play review prompt when the reveal is dismissed.
        if (t.alreadyProcessed || t.suppressPostRideCelebrations) return null

        val kind = when {
            t.isFirstRide -> RevealKind.FIRST_RIDE
            t.isDistancePR -> RevealKind.DISTANCE_PR
            t.isDurationPR -> RevealKind.DURATION_PR
            t.milestoneRideCount != null -> RevealKind.MILESTONE
            else -> RevealKind.DEFAULT
        }

        return Reveal(
            rideId = t.rideId,
            kind = kind,
            totalRides = t.totalRides,
            distanceMeters = t.distanceMeters,
            durationMillis = t.durationMillis,
            milestoneRideCount = t.milestoneRideCount
        )
    }
}
