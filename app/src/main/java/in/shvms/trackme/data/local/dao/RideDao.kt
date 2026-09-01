package `in`.shvms.trackme.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.RideEntity
import `in`.shvms.trackme.data.local.entity.RideWithPoints
import kotlinx.coroutines.flow.Flow

/** History-list projection. Deliberately excludes the relation to gps_points. */
data class HistoryRideSummary(
    val id: Long,
    val startTime: Long,
    val endTime: Long?,
    val isSynced: Boolean,
    val firestoreId: String?,
    val title: String?,
    val persona: String,
    val isSample: Boolean,
    val pendingDelete: Boolean,
    val distance: Double?,
    val avgSpeed: Float?,
    val dashboardActiveDurationMillis: Long,
    val dashboardMetadataVersion: Int,
    val dashboardPointCount: Int,
    /** TASK-231: the card's route shape, on the row. Still no join to gps_points. */
    val dashboardRoutePolyline: String?,
    /** TASK-232: recorded during a group session. */
    val wasGroupRide: Boolean,
    /** TASK-232: riders in that group, including this one. Null when never observed. */
    val groupRiderCount: Int?,
)

@Dao
@JvmSuppressWildcards
interface RideDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRide(ride: RideEntity): Long

    @Update
    suspend fun updateRide(ride: RideEntity): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGPSPoint(point: GPSPointEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGPSPoints(points: List<GPSPointEntity>): List<Long>

    @Query("SELECT id FROM rides WHERE isSample = 1 LIMIT 1")
    suspend fun getSampleRideId(): Long?

    /**
     * TASK-275: the import duplicate check, by track identity rather than by file identity.
     *
     * Excludes rows on their way out, so re-importing a ride you have just deleted is allowed --
     * `pendingDelete` means the user asked for it gone, and refusing the re-import would strand
     * them until the cloud batch commits.
     */
    @Query("SELECT COUNT(*) FROM rides WHERE contentHash = :hash AND pendingDelete = 0")
    suspend fun countByContentHash(hash: String): Int

    @Transaction
    @Query("SELECT * FROM rides ORDER BY startTime DESC")
    fun getAllRidesWithPoints(): Flow<List<RideWithPoints>>

    @Transaction
    @Query("SELECT * FROM rides ORDER BY startTime DESC")
    suspend fun getAllRidesWithPointsSync(): List<RideWithPoints>


    @Transaction
    @Query("SELECT * FROM rides WHERE endTime IS NOT NULL AND endTime > 0 ORDER BY startTime DESC")
    fun getAllCompletedRidesWithPoints(): Flow<List<RideWithPoints>>

    @Query(
        """
        SELECT id, startTime, endTime, isSynced, firestoreId, title, persona, isSample,
               pendingDelete, distance, avgSpeed, dashboardActiveDurationMillis,
               dashboardMetadataVersion, dashboardPointCount, dashboardRoutePolyline,
               wasGroupRide, groupRiderCount
        FROM rides
        WHERE endTime IS NOT NULL AND endTime > 0
        ORDER BY startTime DESC
        """
    )
    fun getAllCompletedRideSummaries(): Flow<List<HistoryRideSummary>>

    /**
     * TASK-232: Community's list. The rider's own rides, recorded while a group was live.
     *
     * Same projection and the same deliberate exclusion of gps_points as the History list -- this
     * is a read of local ride records and nothing else. There is no membership table to join
     * against and there is not going to be one.
     */
    @Query(
        """
        SELECT id, startTime, endTime, isSynced, firestoreId, title, persona, isSample,
               pendingDelete, distance, avgSpeed, dashboardActiveDurationMillis,
               dashboardMetadataVersion, dashboardPointCount, dashboardRoutePolyline,
               wasGroupRide, groupRiderCount
        FROM rides
        WHERE endTime IS NOT NULL AND endTime > 0
          AND wasGroupRide = 1
          AND pendingDelete = 0
        ORDER BY startTime DESC
        """
    )
    fun getGroupRideSummaries(): Flow<List<HistoryRideSummary>>

    @Query("SELECT * FROM rides WHERE endTime IS NULL OR endTime <= 0")
    suspend fun getUncompletedRides(): List<RideEntity>

    @Transaction
    @Query("SELECT * FROM rides WHERE id = :rideId")
    suspend fun getRideWithPointsById(rideId: Long): RideWithPoints?

    @Query("SELECT * FROM rides WHERE id = :rideId")
    fun getRideFlow(rideId: Long): Flow<RideEntity?>
    
    @Query("SELECT * FROM gps_points WHERE rideId = :rideId ORDER BY timestamp ASC")
    fun getPointsForRide(rideId: Long): Flow<List<GPSPointEntity>>

    @Query("SELECT * FROM gps_points WHERE rideId = :rideId ORDER BY timestamp ASC")
    suspend fun getPointsForRideSync(rideId: Long): List<GPSPointEntity>

    @Query("DELETE FROM rides WHERE id = :rideId")
    suspend fun deleteRide(rideId: Long): Int

    /**
     * SCOPE_1.7.3 §0 contract 5 — mark a ride as on its way out, before the cloud batch runs.
     *
     * Written as a targeted UPDATE rather than through [updateRide] so it cannot race with any
     * other in-flight edit of the same row and accidentally write back a stale copy of the ride.
     */
    @Query("UPDATE rides SET pendingDelete = :pending WHERE id = :rideId")
    suspend fun setPendingDelete(rideId: Long, pending: Boolean): Int

    /** Rides flagged for deletion that never completed one — swept at startup. */
    @Query("SELECT * FROM rides WHERE pendingDelete = 1")
    suspend fun getPendingDeleteRides(): List<RideEntity>

    @Query("DELETE FROM gps_points WHERE rideId = :rideId")
    suspend fun deletePointsForRide(rideId: Long): Int

    @Query("DELETE FROM gps_points WHERE rideId IN (SELECT id FROM rides WHERE isSynced = 1)")
    suspend fun deleteSyncedPoints(): Int

    @Query("DELETE FROM rides WHERE isSynced = 1")
    suspend fun deleteSyncedRides(): Int

    @Query("UPDATE rides SET isSynced = 0, firestoreId = NULL")
    suspend fun markAllAsUnsynced(): Int

    @Query("DELETE FROM gps_points")
    suspend fun deleteAllPoints(): Int

    @Query("DELETE FROM rides")
    suspend fun deleteAllRides(): Int
}
