package `in`.shvms.trackme.domain.processor

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.isExplicitAutoPause

/** Returns exactly one marker for every contiguous automatic-pause interval. */
internal fun autoPauseMarkerLocations(points: List<GPSPointEntity>): List<RouteCoordinate> {
    if (points.isEmpty()) return emptyList()

    val markers = mutableListOf<RouteCoordinate>()
    val cluster = mutableListOf<GPSPointEntity>()

    fun flushCluster() {
        if (cluster.isEmpty()) return
        markers += RouteCoordinate(
            latitude = cluster.map { it.latitude }.average(),
            longitude = cluster.map { it.longitude }.average(),
        )
        cluster.clear()
    }

    points.forEach { point ->
        if (point.isExplicitAutoPause) cluster += point else flushCluster()
    }
    flushCluster()
    return markers
}
