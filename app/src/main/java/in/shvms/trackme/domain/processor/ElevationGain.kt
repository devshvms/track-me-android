package `in`.shvms.trackme.domain.processor

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import kotlin.math.max
import kotlin.math.min

/**
 * Computes persisted total ascent from a denoised altitude trace.
 *
 * The centered, edge-truncated moving average is deliberately shared by every
 * post-ride path so a noisy flat route cannot become confident climbing.
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

    return smoothed.zipWithNext()
        .sumOf { (previous, current) ->
            (current - previous).takeIf { it >= NOISE_FLOOR_METERS } ?: 0.0
        }
}

private const val WINDOW_SIZE = 5
private const val HALF_WINDOW = WINDOW_SIZE / 2
private const val MIN_VALID_POINTS = 10
private const val NOISE_FLOOR_METERS = 2.0
