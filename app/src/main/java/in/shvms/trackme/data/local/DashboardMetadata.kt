package `in`.shvms.trackme.data.local

import `in`.shvms.trackme.data.local.entity.RideEntity

const val HOME_DASHBOARD_METADATA_VERSION = 1

/** Applies the one canonical qualification rule after aggregate metadata has been persisted. */
fun withDashboardMetadata(ride: RideEntity, activeDurationMillis: Long): RideEntity {
    val duration = activeDurationMillis.coerceAtLeast(0L)
    val distance = ride.postRideCalculation?.distance ?: 0.0
    val complete = ride.endTime?.let { it > ride.startTime } == true && ride.postRideCalculation != null
    val junk = distance < 10.0 && duration < 120_000L
    return ride.copy(
        qualifiesForStats = complete && !ride.isSample && !ride.pendingDelete && !junk,
        dashboardActiveDurationMillis = duration,
        dashboardMetadataVersion = HOME_DASHBOARD_METADATA_VERSION,
    )
}
