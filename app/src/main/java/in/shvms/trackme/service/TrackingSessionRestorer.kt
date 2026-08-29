package `in`.shvms.trackme.service

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.domain.processor.GeoDistanceCalculator

data class RestoredTrackingMetrics(
    val distanceMeters: Float,
    val activeDurationMillis: Long,
    val elapsedDurationMillis: Long,
    val latestSpeedMetersPerSecond: Float,
    val isPaused: Boolean
)

/** Rebuilds the small set of HUD metrics needed when a tracking process is recreated. */
object TrackingSessionRestorer {
    fun calculate(
        rideStartTime: Long,
        points: List<GPSPointEntity>,
        nowMillis: Long,
        distanceCalculator: GeoDistanceCalculator = AndroidGeoDistanceCalculator
    ): RestoredTrackingMetrics {
        var distanceMeters = 0f
        var activeDurationMillis = 0L

        for (index in 1 until points.size) {
            val previous = points[index - 1]
            val current = points[index]
            val distance = distanceCalculator.meters(previous, current)
            val deltaMillis = current.timestamp - previous.timestamp
            // TASK-259: the one shared rule. A recovered ride must not get a different duration
            // from the same points than a normally finalised one.
            if (`in`.shvms.trackme.data.local.countsAsMovingTime(previous, current)) {
                activeDurationMillis += deltaMillis
                if (distance >= 1.5f && current.speed > 0.3f) {
                    distanceMeters += distance
                }
            }
        }

        return RestoredTrackingMetrics(
            distanceMeters = distanceMeters,
            activeDurationMillis = activeDurationMillis,
            elapsedDurationMillis = (nowMillis - rideStartTime).coerceAtLeast(0L),
            latestSpeedMetersPerSecond = points.lastOrNull()?.speed ?: 0f,
            isPaused = points.lastOrNull()?.isPaused == true
        )
    }

    private val AndroidGeoDistanceCalculator = GeoDistanceCalculator { from, to ->
        val result = FloatArray(1)
        android.location.Location.distanceBetween(
            from.latitude,
            from.longitude,
            to.latitude,
            to.longitude,
            result
        )
        result[0]
    }
}
