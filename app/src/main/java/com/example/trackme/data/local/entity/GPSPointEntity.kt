package com.example.trackme.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "gps_points")
data class GPSPointEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val rideId: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Float,
    val speed: Float,
    val timestamp: Long,
    val isPaused: Boolean
)
