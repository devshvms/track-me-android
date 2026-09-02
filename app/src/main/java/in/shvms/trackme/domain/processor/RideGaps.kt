package `in`.shvms.trackme.domain.processor

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.isManualPauseBoundary
import `in`.shvms.trackme.domain.model.RidePersona

/**
 * TASK-257, shvm: a stretch of a ride that was never recorded.
 *
 * A rider paused manually, walked their bike two streets over, and resumed. The recorder wrote
 * nothing in between, so the two points either side sit adjacent in storage with `isPaused = false`
 * on both — a manual pause leaves **no marker in the point stream**. Everything downstream then
 * treats the jump as a travelled segment: the distance is added, and the map draws a straight line
 * through buildings the rider never went through.
 *
 * Auto-pause is already handled and is not this problem: it flags its points, and the aggregate
 * skips any segment touching a flagged point. This covers what that cannot see — a manual pause,
 * a tunnel, a killed app.
 *
 * ### The rule, and why it takes two signals
 *
 * A gap is **a time gap _and_ a speed the persona could not have reached**. shvm's proposal, and it
 * is better than time alone, which was the first suggestion:
 *
 * - Time gap, plausible speed → the straight line is a fair approximation of what happened. Count
 *   it and draw it solid. A 30-second sampling gap where the rider moved ten metres is not a lie.
 * - Time gap, implausible speed → we *know* the straight line is not the path taken. Counting it
 *   misstates the ride and drawing it solid asserts a route nobody rode.
 *
 * **It must be AND, never OR.** Implied speed alone fires on a single GPS jitter spike — at 1 Hz one
 * bad fix implies an absurd speed — and dropping that segment would discard a real part of the
 * ride. The time gap is what distinguishes "we stopped recording" from "one fix was noisy".
 *
 * ### The ceilings are deliberately generous
 *
 * The two failure directions are not symmetric. A false negative is a cosmetically solid line and a
 * slightly generous distance. A false positive **deletes real distance from a rider's own ride**,
 * silently, with no way for them to tell. So every limit here is set well above what the persona
 * plausibly sustains, and anything unknown resolves to the most permissive.
 */
object RideGaps {

    /**
     * 25 seconds, matching the "GPS signal gaps" figure already shown in Recording details and
     * iOS's `ChartAccessibility.gapThresholdSeconds`. Reused rather than re-picked: two thresholds
     * for the same idea drift, and a rider comparing the gap count to a dotted line should see them
     * agree.
     */
    const val GAP_THRESHOLD_MILLIS = 25_000L

    /**
     * Above what a persona could plausibly sustain between two fixes, in m/s.
     *
     * Ceilings, not typical speeds — a cyclist descending touches 70 km/h, so cycling's limit is
     * there and not at a comfortable 25. `AUTO` takes the highest of all, because an unknown
     * activity must never be the reason a real segment is discarded.
     */
    fun maxPlausibleSpeedMps(persona: RidePersona): Float = when (persona) {
        RidePersona.WALK -> 12f / 3.6f
        RidePersona.RUN -> 30f / 3.6f
        RidePersona.CYCLING -> 80f / 3.6f
        RidePersona.BIKE_DRIVE -> 160f / 3.6f
        RidePersona.CAR_DRIVE -> 220f / 3.6f
        // Unknown activity: the most permissive ceiling, so AUTO never discards a real segment.
        RidePersona.AUTO -> 220f / 3.6f
    }

    /**
     * Whether the straight line from [previous] to [current] represents a stretch that was never
     * recorded, and so should neither be counted nor drawn as a route.
     */
    fun isUnrecordedGap(
        previous: GPSPointEntity,
        current: GPSPointEntity,
        persona: RidePersona,
    ): Boolean {
        val elapsedMillis = current.timestamp - previous.timestamp
        if (elapsedMillis <= GAP_THRESHOLD_MILLIS) return false

        // Non-positive time cannot imply a speed; treat it as ordinary rather than inventing one.
        if (elapsedMillis <= 0L) return false

        val metres = haversineMetres(previous, current)
        val impliedSpeedMps = metres / (elapsedMillis / 1000.0)
        return impliedSpeedMps > maxPlausibleSpeedMps(persona)
    }

    /**
     * Splits a ride into the runs that were actually recorded.
     *
     * Each returned list is a contiguous stretch to draw as a solid line; the space *between* two
     * consecutive runs is a gap, to be drawn dotted. Returning runs rather than a flag per point
     * keeps the renderer honest — it cannot accidentally draw a continuous path through a gap,
     * because it never holds one.
     */
    fun recordedRuns(
        points: List<GPSPointEntity>,
        persona: RidePersona,
    ): List<List<GPSPointEntity>> {
        if (points.isEmpty()) return emptyList()
        val runs = mutableListOf<MutableList<GPSPointEntity>>()
        var run = mutableListOf<GPSPointEntity>()
        var previousPoint: GPSPointEntity? = null

        points.forEach { point ->
            val previous = previousPoint
            val manualBoundaryEndsHere = previous?.isManualPauseBoundary == true
            if (previous != null && (manualBoundaryEndsHere || isUnrecordedGap(previous, point, persona))) {
                if (run.isNotEmpty()) runs += run
                run = mutableListOf()
            }
            run += point
            previousPoint = point
        }
        if (run.isNotEmpty()) runs += run
        return runs
    }

    /** Metres between two fixes. Shared so distance and rendering cannot disagree on the number. */
    fun haversineMetres(a: GPSPointEntity, b: GPSPointEntity): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val h = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2) * kotlin.math.cos(lat1) * kotlin.math.cos(lat2)
        return 2 * earthRadius * kotlin.math.asin(kotlin.math.min(1.0, kotlin.math.sqrt(h)))
    }
}
