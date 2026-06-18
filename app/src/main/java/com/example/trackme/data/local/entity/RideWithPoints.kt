package com.example.trackme.data.local.entity

import androidx.room.Embedded
import androidx.room.Relation

data class RideWithPoints(
    @Embedded val ride: RideEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "rideId"
    )
    val points: List<GPSPointEntity>
)
