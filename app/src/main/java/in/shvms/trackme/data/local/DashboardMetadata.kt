package `in`.shvms.trackme.data.local

import `in`.shvms.trackme.data.local.entity.RideEntity
import `in`.shvms.trackme.data.local.entity.GPSPointEntity

const val HOME_DASHBOARD_METADATA_VERSION = 2

/** Applies the one canonical qualification rule after aggregate metadata has been persisted. */
fun withDashboardMetadata(
    ride: RideEntity,
    activeDurationMillis: Long,
    pointCount: Int = ride.dashboardPointCount,
): RideEntity {
    val duration = activeDurationMillis.coerceAtLeast(0L)
    val distance = ride.postRideCalculation?.distance ?: 0.0
    val complete = ride.endTime?.let { it > ride.startTime } == true && ride.postRideCalculation != null
    val junk = distance < 10.0 && duration < 120_000L
    return ride.copy(
        qualifiesForStats = complete && !ride.isSample && !ride.pendingDelete && !junk,
        dashboardActiveDurationMillis = duration,
        dashboardPointCount = pointCount.coerceAtLeast(0),
        dashboardMetadataVersion = HOME_DASHBOARD_METADATA_VERSION,
    )
}

/** Marks a completed row as reconciled without pretending unknown active time is wall time. */
fun withUnavailableDashboardMetadata(ride: RideEntity, pointCount: Int): RideEntity = ride.copy(
    qualifiesForStats = false,
    dashboardActiveDurationMillis = 0L,
    dashboardPointCount = pointCount.coerceAtLeast(0),
    dashboardMetadataVersion = HOME_DASHBOARD_METADATA_VERSION,
)

/**
 * Reconstructs the same pause-excluded interval sum used at recording finalisation. A single point
 * cannot prove a duration, so null means "unknown" and keeps the ride out of dashboard summaries.
 */
fun dashboardActiveDurationFromPoints(points: List<GPSPointEntity>): Long? {
    if (points.size < 2) return null
    var activeDurationMillis = 0L
    for (index in 1 until points.size) {
        val previous = points[index - 1]
        val current = points[index]
        val gap = current.timestamp - previous.timestamp
        if (!previous.isPaused && !current.isPaused && gap > 0L) {
            activeDurationMillis += gap
        }
    }
    return activeDurationMillis
}
