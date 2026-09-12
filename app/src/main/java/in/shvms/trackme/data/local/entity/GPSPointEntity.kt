package `in`.shvms.trackme.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "gps_points", indices = [androidx.room.Index("rideId")])
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
    val isPaused: Boolean,
    val pauseOrigin: PauseOrigin? = null,
    val cumulativeDistanceMeters: Double? = null,
    /**
     * Presentation-only coordinate emitted by the V2 route estimator. The raw latitude/longitude
     * above remain immutable recording evidence for GPX, sync diagnostics and future reprocessing.
     * Null is the backward-compatible identity value for legacy and pre-1.8.9 rides.
     */
    val displayLatitude: Double? = null,
    val displayLongitude: Double? = null,
)

private val GPSPointEntity.hasValidDisplayCoordinate: Boolean
    get() {
        val latitude = displayLatitude
        val longitude = displayLongitude
        return latitude != null && longitude != null &&
            latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
    }

val GPSPointEntity.presentationLatitude: Double
    get() = if (hasValidDisplayCoordinate) displayLatitude!! else latitude

val GPSPointEntity.presentationLongitude: Double
    get() = if (hasValidDisplayCoordinate) displayLongitude!! else longitude
