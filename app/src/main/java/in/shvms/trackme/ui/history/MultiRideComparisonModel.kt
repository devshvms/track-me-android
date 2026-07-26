package `in`.shvms.trackme.ui.history

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.RideWithPoints
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The comparison surface deliberately has a small, deterministic upper bound. */
internal const val MAX_COMPARISON_RIDES = 8
internal const val COMPARISON_PRIVACY_TRIM_METERS = 200.0

internal data class ComparisonRoute(
    val ride: RideWithPoints,
    val label: String,
    val points: List<GPSPointEntity>
)

internal data class ComparisonConnector(
    val from: GPSPointEntity,
    val to: GPSPointEntity,
    val fromLabel: String,
    val toLabel: String
)

/**
 * Orders rides oldest-first (A is always the first ride), caps the set, and applies the same
 * endpoint privacy trim used by share artifacts. Empty rides remain selectable, but are omitted
 * from map geometry by the screen.
 */
internal fun prepareComparisonRoutes(
    rides: List<RideWithPoints>,
    maxRides: Int = MAX_COMPARISON_RIDES
): List<ComparisonRoute> {
    require(maxRides > 0) { "maxRides must be positive" }
    return rides
        .sortedWith(compareBy<RideWithPoints> { it.ride.startTime }.thenBy { it.ride.id })
        .take(maxRides.coerceAtMost(MAX_COMPARISON_RIDES))
        .mapIndexed { index, ride ->
            ComparisonRoute(
                ride = ride,
                label = comparisonLabel(index),
                points = trimComparisonEndpoints(ride.points)
            )
        }
}

internal fun comparisonLabel(index: Int): String {
    require(index >= 0) { "index must be non-negative" }
    return if (index < 26) ('A'.code + index).toChar().toString() else "R${index + 1}"
}

internal fun comparisonConnectors(routes: List<ComparisonRoute>): List<ComparisonConnector> =
    routes.zipWithNext().mapNotNull { (previous, next) ->
        val from = previous.points.lastOrNull() ?: return@mapNotNull null
        val to = next.points.firstOrNull() ?: return@mapNotNull null
        ComparisonConnector(from, to, previous.label, next.label)
    }

/**
 * Removes up to 200 m at each endpoint. A sparse/short route is returned unchanged so the map
 * still has a useful marker and never crashes while building bounds.
 */
internal fun trimComparisonEndpoints(
    points: List<GPSPointEntity>,
    trimMeters: Double = COMPARISON_PRIVACY_TRIM_METERS
): List<GPSPointEntity> {
    if (points.size < 3 || trimMeters <= 0.0) return points
    val totalDistance = points.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }
    if (totalDistance <= trimMeters * 2.0) return points

    var startDistance = 0.0
    var startIndex = 0
    while (startIndex < points.lastIndex && startDistance < trimMeters) {
        startDistance += distanceMeters(points[startIndex], points[startIndex + 1])
        startIndex++
    }

    var endDistance = 0.0
    var endIndex = points.lastIndex
    while (endIndex > startIndex && endDistance < trimMeters) {
        endDistance += distanceMeters(points[endIndex - 1], points[endIndex])
        endIndex--
    }
    return points.subList(startIndex, endIndex + 1)
}

private fun distanceMeters(a: GPSPointEntity, b: GPSPointEntity): Double {
    val earthRadius = 6_371_000.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return earthRadius * 2.0 * atan2(sqrt(h), sqrt(1.0 - h))
}
