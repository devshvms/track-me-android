package `in`.shvms.trackme.ui.history

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal const val PAUSED_MARKER_MIN_POINTS = 4
internal const val PAUSED_MARKER_MERGE_RADIUS_METERS = 30.0
internal const val PAUSED_MARKER_MAX_COUNT = 3

internal data class PausedMarkerLocation(
    val latitude: Double,
    val longitude: Double
)

/**
 * Produces a set of pause markers for the ride map strictly from recorded auto-pause intervals.
 *
 * TASK-270: A contiguous interval containing one or more persisted `isPaused == true`
 * samples is a recorded auto-pause event. Every such interval renders exactly one pause
 * circle. Marker decluttering must not silently remove an explicit event, and slow speed
 * alone is never promoted to an event.
 */
internal fun explicitPauseMarkerLocations(
    points: List<GPSPointEntity>
): List<PausedMarkerLocation> {
    if (points.isEmpty()) return emptyList()

    val markers = mutableListOf<PausedMarkerLocation>()
    val currentCluster = mutableListOf<GPSPointEntity>()

    fun flushCluster() {
        if (currentCluster.isNotEmpty()) {
            val center = centroid(currentCluster)
            markers.add(PausedMarkerLocation(center.latitude, center.longitude))
            currentCluster.clear()
        }
    }

    for (point in points) {
        if (point.isPaused) {
            currentCluster.add(point)
        } else {
            flushCluster()
        }
    }
    flushCluster()

    return markers
}

private data class Coordinate(val latitude: Double, val longitude: Double)

private fun centroid(points: List<GPSPointEntity>): Coordinate = Coordinate(
    latitude = points.map { it.latitude }.average(),
    longitude = points.map { it.longitude }.average()
)

private fun distanceMeters(first: GPSPointEntity, second: GPSPointEntity): Double =
    distanceMeters(Coordinate(first.latitude, first.longitude), Coordinate(second.latitude, second.longitude))

private fun distanceMeters(first: Coordinate, second: Coordinate): Double {
    val earthRadiusMeters = 6_371_000.0
    val dLat = Math.toRadians(second.latitude - first.latitude)
    val dLon = Math.toRadians(second.longitude - first.longitude)
    val lat1 = Math.toRadians(first.latitude)
    val lat2 = Math.toRadians(second.latitude)
    val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
    return earthRadiusMeters * 2 * atan2(sqrt(a), sqrt(1 - a))
}
