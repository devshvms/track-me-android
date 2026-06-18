package com.example.trackme.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey

data class PostRideCalculation(
    val maxSpeed: Float,
    val distance: Double,
    val avgSpeed: Float,
    val pauseDuration: Long
)

@Entity(tableName = "rides")
data class RideEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startTime: Long,
    val endTime: Long? = null,
    val sourceInfo: String = "Android Device",
    val isBroadcasted: Boolean = false,
    val isSynced: Boolean = false,
    val firestoreId: String? = null,
    val title: String? = null,
    @Embedded
    val postRideCalculation: PostRideCalculation? = null
)
