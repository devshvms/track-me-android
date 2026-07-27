package `in`.shvms.trackme.ui.history

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.RideWithPoints
import `in`.shvms.trackme.domain.export.trimGpsPointsForExport

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

/** Builds the export legend without exposing ride titles to analytics or deep-link metadata. */
internal fun aggregatePreviewLegend(
    routes: List<ComparisonRoute>,
    fallbackTitle: String,
    showLegend: Boolean
): List<Pair<String, String>> = if (showLegend) {
    routes.take(MAX_COMPARISON_RIDES).map { route ->
        route.label to (route.ride.ride.title?.ifBlank { fallbackTitle } ?: fallbackTitle)
    }
} else {
    emptyList()
}

/**
 * Removes up to 200 m at each endpoint. A sparse/short route is returned unchanged so the map
 * still has a useful marker and never crashes while building bounds.
 */
internal fun trimComparisonEndpoints(
    points: List<GPSPointEntity>,
    trimMeters: Double = COMPARISON_PRIVACY_TRIM_METERS
): List<GPSPointEntity> = trimGpsPointsForExport(points, trimMeters)
