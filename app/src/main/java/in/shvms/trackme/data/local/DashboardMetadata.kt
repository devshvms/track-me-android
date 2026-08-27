package `in`.shvms.trackme.data.local

import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.PolyUtil
import `in`.shvms.trackme.data.local.dao.HomeDashboardRoutePoint
import `in`.shvms.trackme.data.local.entity.RideEntity
import `in`.shvms.trackme.data.local.entity.GPSPointEntity

/**
 * Bumped to 3 by TASK-231, which adds [RideEntity.dashboardRoutePolyline] to the rebuildable
 * metadata set. The bump is what backfills it: existing rows fall below the version, the bounded
 * reconciler sweeps them, and every row leaves the candidate set exactly once. A row whose points
 * were pruned simply reconciles to a null polyline -- it does not stay a candidate forever, which
 * a "polyline IS NULL" backfill condition would have caused.
 */
const val HOME_DASHBOARD_METADATA_VERSION = 3

/**
 * TASK-246, shvm: "default thumbnail only for less than 50 points or distance is 0 or no points".
 *
 * Below this, a drawn shape is worse than no shape. A handful of samples renders as a stray tick
 * that reads like a broken route rather than a short one, and a ride that never moved normalises
 * against a zero span and comes out a dot in the middle of the tile. The glyph says "nothing to
 * show here" honestly; a two-point scribble does not.
 */
const val ROUTE_THUMBNAIL_MIN_POINTS = 50

/** The single rule for whether a History thumbnail draws a route or falls back to the glyph. */
fun routeThumbnailDrawsShape(pointCount: Int, distanceMeters: Double): Boolean =
    pointCount >= ROUTE_THUMBNAIL_MIN_POINTS && distanceMeters > 0.0

/**
 * Point budget for the stored thumbnail shape. 40 vertices is more than a 52dp box can resolve and
 * holds the encoded string to a few hundred bytes, so the History projection stays a single-row read.
 */
const val DASHBOARD_ROUTE_POLYLINE_POINTS = 40

/**
 * The stored thumbnail shape, in the same encoded-polyline format the export path has used since
 * 1.8.4 (`ImageExporter` -> `PolyUtil.encode`). Derived from the same point list that produces the
 * count, so the two facts on the row can never disagree about whether a ride has a route.
 *
 * Sampling reuses [HomeDashboardRepository.downsampleRoute] rather than a second thinning rule --
 * one definition of "which points survive" for both the Home preview and this.
 */
fun dashboardRoutePolylineFromPoints(points: List<GPSPointEntity>): String? {
    if (points.size < 2) return null
    val sampled = HomeDashboardRepository.downsampleRoute(
        points.map { HomeDashboardRoutePoint(it.latitude, it.longitude) },
        DASHBOARD_ROUTE_POLYLINE_POINTS,
    )
    return PolyUtil.encode(sampled.map { LatLng(it.latitude, it.longitude) })
}

/** Applies the one canonical qualification rule after aggregate metadata has been persisted. */
fun withDashboardMetadata(
    ride: RideEntity,
    activeDurationMillis: Long,
    pointCount: Int = ride.dashboardPointCount,
    // TASK-246: deliberately has no default. It used to default to the ride's existing polyline,
    // which reads as harmless preservation but silently produced null on every path that builds a
    // fresh entity -- cloud download, GPX import, orphan recovery -- while still stamping the
    // current metadata version, so the backfill's version gate skipped those rows forever and they
    // kept the generic glyph. Requiring the argument makes the compiler ask the question at every
    // call site, which is the only reason all five of them are now correct.
    routePolyline: String?,
): RideEntity {
    val duration = activeDurationMillis.coerceAtLeast(0L)
    val distance = ride.postRideCalculation?.distance ?: 0.0
    val complete = ride.endTime?.let { it > ride.startTime } == true && ride.postRideCalculation != null
    val junk = distance < 10.0 && duration < 120_000L
    return ride.copy(
        qualifiesForStats = complete && !ride.isSample && !ride.pendingDelete && !junk,
        dashboardActiveDurationMillis = duration,
        dashboardPointCount = pointCount.coerceAtLeast(0),
        dashboardRoutePolyline = routePolyline,
        dashboardMetadataVersion = HOME_DASHBOARD_METADATA_VERSION,
    )
}

/**
 * Marks a completed row as reconciled without pretending unknown active time is wall time. The
 * route shape is still stored: a ride whose active duration cannot be proven may still have a
 * perfectly drawable track, and the thumbnail is not gated on qualifying for stats.
 */
fun withUnavailableDashboardMetadata(
    ride: RideEntity,
    pointCount: Int,
    // TASK-246: no default, for the reason given on `withDashboardMetadata`.
    routePolyline: String?,
): RideEntity = ride.copy(
    qualifiesForStats = false,
    dashboardActiveDurationMillis = 0L,
    dashboardPointCount = pointCount.coerceAtLeast(0),
    dashboardRoutePolyline = routePolyline,
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
