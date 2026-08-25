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
               rides.dashboardPointCount > 0 AS hasRoute
        FROM rides
        WHERE rides.qualifiesForStats = 1
          AND rides.dashboardMetadataVersion = 2
          AND rides.endTime IS NOT NULL
          AND rides.endTime > 0
          AND rides.pendingDelete = 0
          AND rides.isSample = 0
        ORDER BY rides.startTime DESC, rides.id DESC
        """
    )
    fun observeRides(): Flow<List<HomeDashboardRideProjection>>

    /** Bounded metadata pages keep an upgrade from loading a large history into memory at once. */
    @Query(
        """
        SELECT * FROM rides
        WHERE dashboardMetadataVersion < 2
          AND endTime IS NOT NULL
          AND endTime > 0
        ORDER BY startTime ASC
        LIMIT :limit
        """
    )
    suspend fun getBackfillCandidates(limit: Int): List<RideEntity>

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
