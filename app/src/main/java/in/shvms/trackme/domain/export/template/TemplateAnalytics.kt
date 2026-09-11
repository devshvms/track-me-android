package `in`.shvms.trackme.domain.export.template

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.domain.RideSplit
import `in`.shvms.trackme.domain.fastestSplit
import `in`.shvms.trackme.domain.haversineMeters
import `in`.shvms.trackme.domain.processor.RouteCoordinate
import `in`.shvms.trackme.domain.rideSplits
import `in`.shvms.trackme.domain.splitUnitMeters
import kotlin.math.max
import kotlin.math.min

/**
 * The data the export templates draw beyond the route itself — pace along the line, the elevation
 * band, the split bars and the fastest segment (SCOPE_1.8.9 §6.1, §6.3).
 *
 * Pure: no Android types, no formatting. Every function either returns something honest to draw or
 * returns nothing, and a template that receives nothing omits the element rather than drawing a
 * placeholder (§11 gate 6).
 */

/** The intensity a ride that held one pace is drawn at: the middle of the gradient, not either end. */
internal const val FLAT_PACE_INTENSITY = 0.55f

private const val MIN_MOVING_MPS = 0.3
private const val MIN_SPEED_RANGE_MPS = 0.4
private const val SMOOTHING_WINDOW = 5

/**
 * Per-point pace intensity along the drawn route: 0 is the ride's slow end, 1 its fast end. This is
 * what The Trace's gradient encodes, so the picture shows where the ride was quick without a legend.
 *
 * Normalised between the 10th and 90th percentile of moving speed rather than min and max: a single
 * GPS spike at 90 km/h would otherwise squash the entire real ride into the bottom of the scale. A
 * ride that held one pace gets [FLAT_PACE_INTENSITY] throughout — a gradient over sensor noise
 * would claim variation that did not happen.
 */
internal fun paceIntensities(points: List<GPSPointEntity>): List<Float> {
    if (points.isEmpty()) return emptyList()
    val speeds = points.map { point ->
        if (point.isPaused || !point.speed.isFinite() || point.speed < 0f) 0.0 else point.speed.toDouble()
    }
    val smoothed = smooth(speeds)
    val moving = smoothed.filter { it > MIN_MOVING_MPS }.sorted()
    if (moving.size < 2) return List(points.size) { FLAT_PACE_INTENSITY }
    val low = percentile(moving, 0.10)
    val high = percentile(moving, 0.90)
    if (high - low < MIN_SPEED_RANGE_MPS) return List(points.size) { FLAT_PACE_INTENSITY }
    return smoothed.map { ((it - low) / (high - low)).coerceIn(0.0, 1.0).toFloat() }
}

/** The Instrument's elevation band, bottom-anchored heights in 0..1, one per distance bin. */
internal data class ElevationProfile(
    val heights: List<Float>,
    val gainMeters: Double,
)

/** Below this the band is plotted against this range anyway, so a flat ride looks flat. */
private const val MIN_PLOTTED_RANGE_METERS = 30.0
private const val MIN_VALID_ALTITUDES = 10
private const val PROFILE_BASE = 0.08f
private const val PROFILE_SPAN = 0.84f

/**
 * The elevation band, or null when there is nothing honest to plot (SCOPE_1.8.9 §12 R4).
 *
 * Null when fewer than ten altitudes are usable — the floor `calculateElevationGainMeters` already
 * applies — when every altitude is exactly zero (the device never reported one), or when the ride's
 * stored gain is null, because a band beside a figure the app itself declined to compute would
 * contradict the ride detail screen.
 *
 * Smoothed with the same five-sample window the gain uses, binned by **distance** rather than by
 * sample so a stop does not stretch the x-axis, and plotted against a range of at least 30 m, so a
 * five-metre wobble on a flat road does not come out looking like a mountain.
 */
internal fun elevationProfile(
    points: List<GPSPointEntity>,
    storedGainMeters: Double?,
    bins: Int = 72,
    distanceBetween: (GPSPointEntity, GPSPointEntity) -> Double = ::haversineMeters,
): ElevationProfile? {
    if (storedGainMeters == null || bins < 2) return null
    val usable = points.filter { it.altitude.isFinite() }.sortedBy { it.timestamp }
    if (usable.size < MIN_VALID_ALTITUDES) return null
    if (usable.all { it.altitude == 0.0 }) return null

    val altitudes = smooth(usable.map { it.altitude })
    val cumulative = DoubleArray(usable.size)
    for (index in 1 until usable.size) {
        cumulative[index] = cumulative[index - 1] +
            distanceBetween(usable[index - 1], usable[index]).coerceAtLeast(0.0)
    }
    val total = cumulative.last()
    if (total <= 0.0) return null

    val minimum = altitudes.minOrNull() ?: return null
    val maximum = altitudes.maxOrNull() ?: return null
    val range = max(maximum - minimum, MIN_PLOTTED_RANGE_METERS)

    var cursor = 0
    val heights = List(bins) { bin ->
        val target = total * bin / (bins - 1)
        while (cursor < cumulative.lastIndex - 1 && cumulative[cursor + 1] < target) cursor++
        val start = cumulative[cursor]
        val end = cumulative[min(cursor + 1, cumulative.lastIndex)]
        val fraction = if (end > start) ((target - start) / (end - start)).coerceIn(0.0, 1.0) else 0.0
        val altitude = altitudes[cursor] +
            (altitudes[min(cursor + 1, altitudes.lastIndex)] - altitudes[cursor]) * fraction
        (PROFILE_BASE + PROFILE_SPAN * ((altitude - minimum) / range)).toFloat()
    }
    return ElevationProfile(heights = heights, gainMeters = storedGainMeters)
}

/** One bar of The Instrument's splits chart: taller is faster. */
internal data class SplitBar(
    val heightFraction: Float,
    /** Where this split's speed sits between the ride's slowest and fastest full split. */
    val speedFraction: Float,
    val isFastest: Boolean,
    val isPartial: Boolean,
)

/**
 * The splits chart's bars. Height encodes speed, so the chart reads "taller is quicker" without an
 * axis. The slowest split still stands at 30 % — a bar of zero height reads as a missing split.
 *
 * The range is taken over **full** splits only, for the same reason [fastestSplit] excludes the
 * trailing partial: a 200 m remainder run flat out is not comparable to a kilometre.
 */
internal fun splitBars(splits: List<RideSplit>): List<SplitBar> {
    val full = splits.filter { !it.isPartial && it.averageSpeedMps > 0.0 }
    if (full.isEmpty()) return emptyList()
    val fastest = fastestSplit(splits)
    val slowestSpeed = full.minOf { it.averageSpeedMps }
    val range = full.maxOf { it.averageSpeedMps } - slowestSpeed
    return splits.filter { it.averageSpeedMps > 0.0 }.map { split ->
        val fraction = if (range < 1e-6) 0.6 else ((split.averageSpeedMps - slowestSpeed) / range).coerceIn(0.0, 1.0)
        SplitBar(
            heightFraction = (0.3 + 0.7 * fraction).toFloat(),
            speedFraction = fraction.toFloat(),
            isFastest = split === fastest,
            isPartial = split.isPartial,
        )
    }
}

/**
 * The coordinates of the fastest full split, clipped to [drawn] — the route actually on the canvas,
 * which after the privacy trim starts and ends 200 m in. Null when there is no full split, or when
 * the fastest one lies entirely inside a trimmed end.
 *
 * Distance accumulates under exactly the rules `rideSplits` uses — paused legs and legs shorter than
 * [minLegMeters] add nothing — so the gold segment is the kilometre the splits chart calls fastest,
 * not a neighbour of it. The boundary samples on either side are kept so the highlight meets the
 * line rather than stopping short of it.
 */
internal fun fastestSplitSegment(
    points: List<GPSPointEntity>,
    imperial: Boolean,
    drawn: List<GPSPointEntity>,
    minLegMeters: Float = 3.5f,
    distanceBetween: (GPSPointEntity, GPSPointEntity) -> Double = ::haversineMeters,
): List<RouteCoordinate>? {
    if (points.size < 2 || drawn.size < 2) return null
    val fastest = fastestSplit(rideSplits(points, imperial, minLegMeters, distanceBetween)) ?: return null
    val unit = splitUnitMeters(imperial)
    val from = (fastest.index - 1) * unit
    val to = fastest.index * unit

    val cumulative = DoubleArray(points.size)
    for (index in 1 until points.size) {
        val current = points[index]
        val leg = if (current.isPaused) 0.0 else distanceBetween(points[index - 1], current)
        cumulative[index] = cumulative[index - 1] + if (leg < minLegMeters) 0.0 else leg
    }
    val first = cumulative.indexOfFirst { it >= from }.let { if (it > 0) it - 1 else it }
    val last = cumulative.indexOfFirst { it >= to }.let { if (it < 0) points.lastIndex else it }
    if (first < 0 || last <= first) return null

    val visibleFrom = drawn.minOf { it.timestamp }
    val visibleTo = drawn.maxOf { it.timestamp }
    val segment = points.subList(first, last + 1)
        .filter { it.timestamp in visibleFrom..visibleTo }
        .map { RouteCoordinate(it.latitude, it.longitude) }
    return segment.takeIf { it.size >= 2 }
}

private fun smooth(values: List<Double>): List<Double> {
    val half = SMOOTHING_WINDOW / 2
    return values.indices.map { index ->
        values.subList(max(0, index - half), min(values.lastIndex, index + half) + 1).average()
    }
}

private fun percentile(sorted: List<Double>, fraction: Double): Double {
    val rank = fraction * (sorted.size - 1)
    val lower = rank.toInt()
    val upper = min(lower + 1, sorted.lastIndex)
    return sorted[lower] + (sorted[upper] - sorted[lower]) * (rank - lower)
}
