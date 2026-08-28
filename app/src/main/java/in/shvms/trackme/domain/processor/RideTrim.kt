package `in`.shvms.trackme.domain.processor

import `in`.shvms.trackme.data.local.entity.GPSPointEntity

/**
 * The stationary head and tail of a ride, which nobody meant to record.
 *
 * @param startIndex first point worth drawing, inclusive.
 * @param endIndex last point worth drawing, inclusive.
 */
data class RideTrim(
    val startIndex: Int,
    val endIndex: Int,
    val leadingMillis: Long,
    val trailingMillis: Long,
) {
    val isTrimmed: Boolean get() = leadingMillis > 0L || trailingMillis > 0L
    val totalTrimmedMillis: Long get() = leadingMillis + trailingMillis
}

/**
 * TASK-253, shvm: a rider starts recording before setting off and forgets to stop after arriving,
 * so the chart ends in a flat line for half an hour and the map carries a blob where they parked.
 *
 * **The framing that makes this cheap: we do not need to know *where* a ride ended, only *that* it
 * did.** shvm asked how to detect the destination — geofences, home tags, learned locations — and
 * none of that is needed. The question "has this rider stopped moving?" is already answered, per
 * persona, by [AdaptiveAutoPauseEngine], and its answer is already written onto every point as
 * `isPaused`.
 *
 * **This is a display window, not an edit.** It stores nothing and deletes nothing: it returns a
 * range for the chart and map to draw. The stats are deliberately untouched, and they are already
 * right — `dashboardActiveDurationFromPoints` excludes paused points, so the forgotten half hour
 * was never in "Duration". It *is* in "Total", correctly, because Total is wall time and the ride
 * really did span it. Nothing here needs an undo, because nothing here is destroyed.
 *
 * **Only the ends.** A pause at a traffic light is interior and must survive — it is part of the
 * ride, and cutting it would silently teleport the route across a junction. Only a leading or
 * trailing run of stationary points is a candidate.
 *
 * `isPaused` alone is not enough. A rider with auto-pause switched off, or a tail the engine never
 * got to evaluate, leaves the flat points unflagged, so a speed test backs it up.
 */
fun rideTrimWindow(
    points: List<GPSPointEntity>,
    pauseSpeedMps: Float,
    minimumRunMillis: Long = DEFAULT_MINIMUM_TRIM_RUN_MILLIS,
): RideTrim {
    val last = points.lastIndex
    if (points.size < MINIMUM_POINTS_TO_TRIM) return RideTrim(0, last.coerceAtLeast(0), 0L, 0L)

    fun stationary(point: GPSPointEntity) = point.isPaused || point.speed <= pauseSpeedMps

    var start = 0
    while (start < last && stationary(points[start])) start++

    var end = last
    while (end > start && stationary(points[end])) end--

    // Everything was stationary. There is no ride to frame, so draw all of it rather than nothing
    // and let the reader see that for themselves.
    if (start >= end) return RideTrim(0, last, 0L, 0L)

    // A brief wait at a kerb is not a forgotten recording. Only trim a run long enough to be the
    // thing shvm described, and keep the points otherwise -- a short flat lead-in is honest.
    val leading = points[start].timestamp - points.first().timestamp
    val trailing = points.last().timestamp - points[end].timestamp

    val trimStart = if (leading >= minimumRunMillis) start else 0
    val trimEnd = if (trailing >= minimumRunMillis) end else last

    return RideTrim(
        startIndex = trimStart,
        endIndex = trimEnd,
        leadingMillis = if (trimStart > 0) leading else 0L,
        trailingMillis = if (trimEnd < last) trailing else 0L,
    )
}

/**
 * Two minutes. Below this a flat run is a level crossing or a chat at the gate, and cutting it
 * would misrepresent the ride; above it, the rider has almost certainly stopped riding. The value
 * is deliberately far above [AdaptiveAutoPauseEngine]'s own stillness thresholds, which are tuned
 * to pause a timer within seconds -- pausing a timer early is cheap and reversible, hiding a
 * portion of someone's route is neither.
 */
const val DEFAULT_MINIMUM_TRIM_RUN_MILLIS = 120_000L

/** Below this there is no shape to trim toward, and the window would be noise. */
private const val MINIMUM_POINTS_TO_TRIM = 4
