package `in`.shvms.trackme.domain

import `in`.shvms.trackme.data.local.entity.GPSPointEntity

/**
 * One completed (or trailing partial) unit of a ride — a kilometre, or a mile in imperial.
 *
 * @param index 1-based, so the first split is "1 km" rather than "0 km".
 * @param distanceMeters how far this split actually covers. Equal to the unit length for every
 *   split except the last, which is whatever was left over.
 * @param movingMillis time spent moving within the split. Auto-paused samples are excluded, for
 *   the same reason they are excluded from distance: a split that counts a coffee stop as slow
 *   running describes the coffee, not the running.
 * @param isPartial true for the trailing remainder. Shown, because dropping it silently loses the
 *   end of the ride, but marked, because its pace is computed over a shorter distance and is not
 *   comparable to a full split.
 */
data class RideSplit(
    val index: Int,
    val distanceMeters: Double,
    val movingMillis: Long,
    val isPartial: Boolean,
) {
    /** Metres per second across the split, or 0 when it recorded no moving time. */
    val averageSpeedMps: Double
        get() = if (movingMillis <= 0L) 0.0 else distanceMeters / (movingMillis / 1000.0)
}

/**
 * The app's definition of moving, in metres per second — the same value `TemplateAnalytics` uses to
 * decide which samples count toward a pace.
 */
const val MIN_SPLIT_MOVING_MPS: Double = 0.3

/** Metres in one split unit. */
fun splitUnitMeters(imperial: Boolean): Double = if (imperial) 1609.344 else 1000.0

/**
 * Cuts a ride into per-unit splits.
 *
 * ### Why this is not just "distance / n"
 *
 * A split boundary almost never lands on a recorded GPS point — you cross 1.000 km somewhere
 * between two samples taken a second apart. Assigning the whole inter-sample leg to whichever side
 * it started on would push every subsequent boundary further out of place, so the legs that
 * straddle a boundary are **divided in proportion**: the fraction of the leg's distance that falls
 * before the boundary takes the same fraction of its time.
 *
 * Without that, splits drift — the tenth kilometre of a run ends up measured over noticeably more
 * or less than a kilometre, and the paces stop being comparable to each other, which is the only
 * thing a splits table is for.
 *
 * @param minLegMeters the noise floor. A leg shorter than this is **held and added to the next
 *   one** rather than discarded — which is the difference between declining to count a wobble as
 *   movement and throwing away the distance it sat on. Discarding it broke every walk: at 1 Hz a
 *   walker covers about 1.3 m per sample, so every leg fell through the floor, nothing reached a
 *   kilometre, and a 4.6 km walk showed a single remainder. The ride's own total comes from the V2
 *   estimator, which has no such floor, so the table and the headline figure disagreed by
 *   kilometres.
 * @param minMovingMps what rescues the floor's *other* job. Carrying every short leg forward would
 *   let a rider standing still while the GPS wanders accumulate metres three at a time, and this
 *   table has no plausibility check of its own to catch that. So carried distance is redeemed only
 *   if it was covered at a moving pace: a walker clears 3.5 m in under three seconds, a stationary
 *   rider takes half a minute, and **speed is the difference the distance floor was always groping
 *   for**. The value is the app's own definition of moving, shared with `TemplateAnalytics`.
 */
fun rideSplits(
    points: List<GPSPointEntity>,
    imperial: Boolean,
    minLegMeters: Float = 3.5f,
    minMovingMps: Double = MIN_SPLIT_MOVING_MPS,
    distanceBetween: (GPSPointEntity, GPSPointEntity) -> Double = ::haversineMeters,
): List<RideSplit> {
    if (points.size < 2) return emptyList()
    val unit = splitUnitMeters(imperial)

    val splits = mutableListOf<RideSplit>()
    var index = 1
    var distanceIntoSplit = 0.0
    var millisIntoSplit = 0L
    // Distance and time from legs too short to clear the noise floor on their own, waiting for the
    // next leg to join. Nothing is dropped; it is only deferred.
    var carryMeters = 0.0
    var carryMillis = 0L

    for (i in 1 until points.size) {
        val previous = points[i - 1]
        val current = points[i]
        // Paused legs contribute nothing at all: not distance, and not the time they took.
        if (current.isPaused) continue

        var legMeters = distanceBetween(previous, current) + carryMeters
        var legMillis = (current.timestamp - previous.timestamp).coerceAtLeast(0L) + carryMillis
        if (legMeters < minLegMeters) {
            carryMeters = legMeters
            carryMillis = legMillis
            continue
        }
        // Only legs that needed carrying are speed-tested: a leg that cleared the floor on its own
        // was already being counted before this rule existed, and quietly dropping it now would be
        // a second, unasked-for change of behaviour.
        if (carryMeters > 0.0 && legMillis > 0L && legMeters / (legMillis / 1000.0) < minMovingMps) {
            carryMeters = 0.0
            carryMillis = 0L
            continue
        }
        carryMeters = 0.0
        carryMillis = 0L

        // A single leg can close more than one split if sampling dropped out for a while, so this
        // consumes the leg in pieces rather than assuming one boundary per leg.
        while (distanceIntoSplit + legMeters >= unit) {
            val remaining = unit - distanceIntoSplit
            val share = if (legMeters > 0.0) remaining / legMeters else 0.0
            val takenMillis = (legMillis * share).toLong()

            splits += RideSplit(
                index = index,
                distanceMeters = unit,
                movingMillis = millisIntoSplit + takenMillis,
                isPartial = false,
            )
            index += 1

            legMeters -= remaining
            legMillis -= takenMillis
            distanceIntoSplit = 0.0
            millisIntoSplit = 0L
        }

        distanceIntoSplit += legMeters
        millisIntoSplit += legMillis
    }

    // Whatever was still being carried belongs to the tail, not to nobody.
    distanceIntoSplit += carryMeters
    millisIntoSplit += carryMillis

    // The remainder, if there is enough of it to mean anything. A two-metre tail is rounding, not
    // a split, and showing it as one would put an absurd pace at the bottom of the table.
    if (distanceIntoSplit >= minLegMeters) {
        splits += RideSplit(
            index = index,
            distanceMeters = distanceIntoSplit,
            movingMillis = millisIntoSplit,
            isPartial = true,
        )
    }
    return splits
}

/**
 * The fastest full split, or null when there is none.
 *
 * Partials are excluded deliberately: a 200 m remainder run flat out would take the crown from a
 * genuinely fast kilometre, and the two are not the same achievement.
 */
fun fastestSplit(splits: List<RideSplit>): RideSplit? =
    splits.filter { !it.isPartial && it.averageSpeedMps > 0.0 }.maxByOrNull { it.averageSpeedMps }

/** Great-circle distance, so the computation stays testable without Android's Location. */
internal fun haversineMeters(a: GPSPointEntity, b: GPSPointEntity): Double {
    val earthRadius = 6_371_000.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.sin(dLon / 2) * Math.sin(dLon / 2) * Math.cos(lat1) * Math.cos(lat2)
    return 2 * earthRadius * Math.asin(Math.sqrt(h.coerceIn(0.0, 1.0)))
}
