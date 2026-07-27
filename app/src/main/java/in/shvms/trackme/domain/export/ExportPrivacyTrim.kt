package `in`.shvms.trackme.domain.export

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal fun gpsDistanceMeters(from: GPSPointEntity, to: GPSPointEntity): Double {
    val earthRadius = 6_371_000.0
    val dLat = Math.toRadians(to.latitude - from.latitude)
    val dLon = Math.toRadians(to.longitude - from.longitude)
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return earthRadius * 2.0 * atan2(sqrt(h), sqrt(1.0 - h))
}

/**
 * Returns a presentation-only route with a distance trim at each end.
 *
 * The stored ride and its GPS points are never mutated. Sparse/short routes that cannot retain
 * two points after trimming are returned unchanged so export remains graceful.
 */
internal fun trimGpsPointsForExport(
    points: List<GPSPointEntity>,
    trimMeters: Double,
    distanceMeters: (GPSPointEntity, GPSPointEntity) -> Double = ::gpsDistanceMeters
): List<GPSPointEntity> {
    if (points.size < 2 || trimMeters <= 0.0) return points

    val segmentDistances = DoubleArray(points.lastIndex)
    var totalMeters = 0.0
    for (index in segmentDistances.indices) {
        val segment = distanceMeters(points[index], points[index + 1]).coerceAtLeast(0.0)
        segmentDistances[index] = segment
        totalMeters += segment
    }

    if (totalMeters <= trimMeters * 2.0) return points

    var startIndex = 0
    var startMeters = 0.0
    while (startIndex < points.lastIndex && startMeters < trimMeters) {
        startMeters += segmentDistances[startIndex]
        startIndex++
    }

    var endIndex = points.lastIndex
    var endMeters = 0.0
    while (endIndex > 0 && endMeters < trimMeters) {
        endMeters += segmentDistances[endIndex - 1]
        endIndex--
    }

    return if (startIndex < endIndex) points.subList(startIndex, endIndex + 1) else points
}
