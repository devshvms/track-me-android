package `in`.shvms.trackme.domain.processor

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import kotlin.math.max
import kotlin.math.min

/**
 * Total ascent: the cumulative sum of positive altitude changes, which is what every fitness
 * platform means by "elevation gain". Not final-minus-initial — that reports roughly zero for any
 * loop, which is most rides.
 *
 * GPS altitude is far noisier than GPS position, so summing raw positive deltas roughly doubles
 * real gain (`SCOPE_1.8.2` §1). Two defences, in order:
 *
 * 1. A 5-point moving average, which removes per-sample jitter.
 * 2. A [NOISE_FLOOR_METERS] threshold measured against a **running reference**, not against the
 *    previous sample. A climb is banked once it stands that far above the lowest point seen since
 *    the last bank; while the trace descends, the reference follows it down.
 *
 * **The reference is the part that matters, and getting it wrong is why this returned 0 for every
 * real ride.** Applying the floor sample-to-sample discards any climb gentle enough that no single
 * pair of consecutive samples clears it — and at 1 Hz, a 100 m climb over ten minutes moves about
 * 0.17 m per sample. Every delta was thrown away and the answer was always exactly zero. It passed
 * its tests because both vectors stepped 100 m in a single sample, which no real ride does.
 *
 * Returns null when there is too little altitude data to say anything, so the caller renders no
 * cell rather than a `0 m` that means "unknown" (§5.2). A genuine 0.0 means a genuinely flat ride.
 */
internal fun calculateElevationGainMeters(points: List<GPSPointEntity>): Double? {
    val altitudes = points.asSequence()
        .filter { it.altitude.isFinite() }
        .sortedBy { it.timestamp }
        .map { it.altitude }
        .toList()
    if (altitudes.size < MIN_VALID_POINTS) return null

    val smoothed = altitudes.indices.map { index ->
        val start = max(0, index - HALF_WINDOW)
        val end = min(altitudes.lastIndex, index + HALF_WINDOW)
        altitudes.subList(start, end + 1).average()
    }

    var reference = smoothed.first()
    var gain = 0.0
    for (altitude in smoothed) {
        val climbed = altitude - reference
        when {
            climbed >= NOISE_FLOOR_METERS -> {
                gain += climbed
                reference = altitude
            }
            // Descending resets the mark the next climb is measured from, so a descent followed by
            // a re-ascent of the same hill is counted once each rather than smeared into one.
            altitude < reference -> reference = altitude
        }
    }
    return gain
}

private const val WINDOW_SIZE = 5
private const val HALF_WINDOW = WINDOW_SIZE / 2
private const val MIN_VALID_POINTS = 10
private const val NOISE_FLOOR_METERS = 2.0
