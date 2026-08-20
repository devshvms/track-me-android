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
 * @param minLegMeters legs shorter than this are treated as stationary noise and contribute
 *   neither distance nor time, matching the threshold the distance total already uses.
 */
fun rideSplits(
    points: List<GPSPointEntity>,
    imperial: Boolean,
    minLegMeters: Float = 3.5f,
    distanceBetween: (GPSPointEntity, GPSPointEntity) -> Double = ::haversineMeters,
): List<RideSplit> {
    if (points.size < 2) return emptyList()
    val unit = splitUnitMeters(imperial)

    val splits = mutableListOf<RideSplit>()
    var index = 1
    var distanceIntoSplit = 0.0
    var millisIntoSplit = 0L

    for (i in 1 until points.size) {
        val previous = points[i - 1]
        val current = points[i]
        // Paused legs contribute nothing at all: not distance, and not the time they took.
        if (current.isPaused) continue

        var legMeters = distanceBetween(previous, current)
        if (legMeters < minLegMeters) continue
        var legMillis = (current.timestamp - previous.timestamp).coerceAtLeast(0L)

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
