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
 * Produces a small, stable set of meaningful stop markers for the ride map.
 *
 * GPS jitter and stop-and-go riding can otherwise turn every few slow samples into
 * a separate pin. Clusters are built from consecutive slow/paused samples, nearby
 * clusters are merged, short clusters are ignored, and the most sustained stops
 * are capped so the map stays legible on long rides.
 */
internal fun pausedMarkerLocations(
    points: List<GPSPointEntity>,
    minPoints: Int = PAUSED_MARKER_MIN_POINTS,
    mergeRadiusMeters: Double = PAUSED_MARKER_MERGE_RADIUS_METERS,
    maxMarkers: Int = PAUSED_MARKER_MAX_COUNT
): List<PausedMarkerLocation> {
    if (points.isEmpty() || maxMarkers <= 0) return emptyList()

    val clusters = mutableListOf<MutableList<GPSPointEntity>>()
    var current = mutableListOf<GPSPointEntity>()

    fun finishCurrent() {
        if (current.isNotEmpty()) clusters += current
        current = mutableListOf()
    }

    points.forEach { point ->
        if (!point.isPaused && point.speed > 0.1f) {
            finishCurrent()
            return@forEach
        }
        val previous = current.lastOrNull()
        if (previous == null || distanceMeters(previous, point) <= mergeRadiusMeters) {
            current += point
        } else {
            finishCurrent()
            current += point
        }
    }
    finishCurrent()

    val sustained = clusters.filter { it.size >= minPoints }
    if (sustained.isEmpty()) return emptyList()

    val merged = mutableListOf<MutableList<GPSPointEntity>>()
    sustained.forEach { cluster ->
        val previous = merged.lastOrNull()
        if (previous != null && distanceMeters(centroid(previous), centroid(cluster)) <= mergeRadiusMeters * 1.5) {
            previous += cluster
        } else {
            merged += cluster.toMutableList()
        }
    }

    return merged
        .sortedWith(compareByDescending<List<GPSPointEntity>> { it.size }.thenBy { it.first().timestamp })
        .take(maxMarkers)
        .sortedBy { it.first().timestamp }
        .map { cluster ->
            val center = centroid(cluster)
            PausedMarkerLocation(center.latitude, center.longitude)
        }
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
