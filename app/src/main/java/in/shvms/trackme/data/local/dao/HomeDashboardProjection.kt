package `in`.shvms.trackme.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import `in`.shvms.trackme.data.local.entity.RideEntity
import kotlinx.coroutines.flow.Flow

/** Metadata-only Room projection. No title, cloud ID, or route point crosses into Home. */
data class HomeDashboardRideProjection(
    val localId: Long,
    val startedAtEpochMillis: Long,
    val startZoneId: String?,
    val personaRaw: String,
    val distanceMeters: Double,
    val activeDurationMillis: Long,
    val avgSpeedMps: Double,
    val hasRoute: Boolean,
    /** TASK-275: RECORDED or IMPORTED; only the former earns levels and milestones. */
    val sourceRaw: String,
)

/** The thumbnail's deliberately narrow second lookup. */
data class HomeDashboardRoutePoint(
    val latitude: Double,
    val longitude: Double,
)

@Dao
@JvmSuppressWildcards
interface HomeDashboardDao {
    /**
     * Projection-only Home source. This query cannot accidentally instantiate RideWithPoints:
     * its result type contains only the aggregate facts the selector is allowed to inspect.
     */
    @Query(
        """
        SELECT rides.id AS localId,
               rides.startTime AS startedAtEpochMillis,
               rides.startZoneId AS startZoneId,
               rides.persona AS personaRaw,
               COALESCE(rides.distance, 0.0) AS distanceMeters,
               rides.dashboardActiveDurationMillis AS activeDurationMillis,
               COALESCE(rides.avgSpeed, 0.0) AS avgSpeedMps,
               rides.dashboardPointCount > 0 AS hasRoute,
               rides.source AS sourceRaw
        FROM rides
        WHERE rides.qualifiesForStats = 1
          AND rides.dashboardMetadataVersion = 4
          AND rides.endTime IS NOT NULL
          AND rides.endTime > 0
          AND rides.pendingDelete = 0
          AND rides.isSample = 0
        ORDER BY rides.startTime DESC, rides.id DESC
        """
    )
    fun observeRides(): Flow<List<HomeDashboardRideProjection>>

    /**
     * Bounded metadata pages keep an upgrade from loading a large history into memory at once.
     *
     * TASK-246: the second clause is the important one. Version alone was not enough — a ride could
     * be stamped with the *current* version and still carry no route shape, because four write
     * paths (cloud download, GPX import, orphan recovery, the compression pass) built metadata
     * without a polyline. Those rows were invisible to a version gate, so they kept the generic
     * glyph permanently. Selecting on the missing shape itself repairs them, and repairs any future
     * path that forgets, instead of needing a version bump per mistake.
     *
     * **This terminates.** `reconcile` always rewrites `dashboardPointCount` from the points it
     * actually read, so a row leaves the candidate set either way: with >= 2 real points it gains a
     * polyline, and with fewer its count is rewritten below 2. The `>= 2` bound is what makes that
     * true — a polyline needs two points, so matching on `> 0` would spin forever on a one-point
     * ride that can never produce one.
     */
    @Query(
        """
        SELECT * FROM rides
        WHERE endTime IS NOT NULL
          AND endTime > 0
          AND (
            dashboardMetadataVersion < 4
            OR (dashboardRoutePolyline IS NULL AND dashboardPointCount >= 2)
          )
        ORDER BY startTime ASC
        LIMIT :limit
        """
    )
    suspend fun getBackfillCandidates(limit: Int): List<RideEntity>

    /**
     * TASK-225. Deliberately separate from [observeRides]: that projection filters isSample = 0 and
     * must keep doing so. One indexed existence check, no route points.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM rides WHERE isSample = 1 AND pendingDelete = 0)")
    fun observeHasSampleRide(): Flow<Boolean>

    @Query("SELECT COUNT(*) FROM gps_points WHERE rideId = :rideId")
    suspend fun getRoutePointCount(rideId: Long): Int

    @Query("SELECT latitude, longitude FROM gps_points WHERE rideId = :rideId ORDER BY timestamp ASC, id ASC LIMIT 1")
    suspend fun getFirstRoutePoint(rideId: Long): HomeDashboardRoutePoint?

    @Query("SELECT latitude, longitude FROM gps_points WHERE rideId = :rideId ORDER BY timestamp DESC, id DESC LIMIT 1")
    suspend fun getLastRoutePoint(rideId: Long): HomeDashboardRoutePoint?

    /** Coordinates only; sampling happens in SQLite, outside summary selection. */
    @Query(
        """
        SELECT latitude, longitude FROM gps_points
        WHERE rideId = :rideId
          AND id != (SELECT MIN(id) FROM gps_points WHERE rideId = :rideId)
          AND id != (SELECT MAX(id) FROM gps_points WHERE rideId = :rideId)
          AND (id - (SELECT MIN(id) FROM gps_points WHERE rideId = :rideId)) % :stride = 0
        ORDER BY timestamp ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun getRouteInteriorPoints(rideId: Long, stride: Int, limit: Int): List<HomeDashboardRoutePoint>
}
