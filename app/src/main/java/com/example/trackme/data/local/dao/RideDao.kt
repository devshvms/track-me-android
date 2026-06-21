package com.example.trackme.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.trackme.data.local.entity.GPSPointEntity
import com.example.trackme.data.local.entity.RideEntity
import com.example.trackme.data.local.entity.RideWithPoints
import kotlinx.coroutines.flow.Flow

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

    @Transaction
    @Query("SELECT * FROM rides ORDER BY startTime DESC")
    fun getAllRidesWithPoints(): Flow<List<RideWithPoints>>

    @Transaction
    @Query("SELECT * FROM rides WHERE id = :rideId")
    suspend fun getRideWithPointsById(rideId: Long): RideWithPoints?

    @Query("SELECT * FROM rides WHERE id = :rideId")
    fun getRideFlow(rideId: Long): Flow<RideEntity?>
    
    @Query("SELECT * FROM gps_points WHERE rideId = :rideId ORDER BY timestamp ASC")
    fun getPointsForRide(rideId: Long): Flow<List<GPSPointEntity>>

    @Query("DELETE FROM rides WHERE id = :rideId")
    suspend fun deleteRide(rideId: Long): Int

    @Query("DELETE FROM gps_points WHERE rideId = :rideId")
    suspend fun deletePointsForRide(rideId: Long): Int

    @Query("DELETE FROM gps_points WHERE rideId IN (SELECT id FROM rides WHERE isSynced = 1)")
    suspend fun deleteSyncedPoints(): Int

    @Query("DELETE FROM rides WHERE isSynced = 1")
    suspend fun deleteSyncedRides(): Int
}
