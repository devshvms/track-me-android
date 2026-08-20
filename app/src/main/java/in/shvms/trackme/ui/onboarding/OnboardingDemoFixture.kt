package `in`.shvms.trackme.ui.onboarding

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.PostRideCalculation
import `in`.shvms.trackme.data.local.entity.RideEntity
import `in`.shvms.trackme.data.local.entity.RideWithPoints
import `in`.shvms.trackme.domain.model.RidePersona

/**
 * Canonical ride used by the onboarding demos and, later, the first-run sample ride.
 *
 * The route is synthetic and centered on a public park; it is not captured user location data.
 * Everything is constructed as plain values so showing a demo can never read or write Room.
 */
object OnboardingDemoFixture {
    const val REFERENCE_START_TIME_MILLIS = 1_767_225_600_000L
    const val DURATION_MILLIS = 540_000L
    const val DISTANCE_METERS = 1_931.404579
    const val AVERAGE_SPEED_METERS_PER_SECOND = DISTANCE_METERS / (DURATION_MILLIS / 1_000.0)
    const val MAX_SPEED_METERS_PER_SECOND = 4.13f
    const val POINT_COUNT = 31

    private data class Sample(
        val latitude: Double,
        val longitude: Double,
        val altitudeMeters: Double,
        val speedMetersPerSecond: Float,
        val accuracyMeters: Float,
    ) {
        fun midpoint(next: Sample) = Sample(
            latitude = (latitude + next.latitude) / 2,
            longitude = (longitude + next.longitude) / 2,
            altitudeMeters = (altitudeMeters + next.altitudeMeters) / 2,
            speedMetersPerSecond = (speedMetersPerSecond + next.speedMetersPerSecond) / 2,
            accuracyMeters = (accuracyMeters + next.accuracyMeters) / 2,
        )
    }

    private val anchors = listOf(
        Sample(12.976698, 77.592085, 918.0, 2.80f, 5.2f),
        Sample(12.977342, 77.592805, 920.0, 2.94f, 4.8f),
        Sample(12.977882, 77.593810, 923.0, 3.45f, 4.5f),
        Sample(12.978108, 77.595055, 927.0, 3.81f, 4.2f),
        Sample(12.977747, 77.596375, 931.0, 4.13f, 4.0f),
        Sample(12.976982, 77.597350, 934.0, 3.77f, 4.1f),
        Sample(12.976008, 77.597755, 936.0, 3.25f, 4.4f),
        Sample(12.974898, 77.597440, 935.0, 3.56f, 4.7f),
        Sample(12.973923, 77.596690, 932.0, 3.76f, 5.0f),
        Sample(12.973247, 77.595655, 928.0, 3.75f, 5.3f),
        Sample(12.973022, 77.594425, 924.0, 3.77f, 5.1f),
        Sample(12.973382, 77.593210, 921.0, 3.82f, 4.8f),
        Sample(12.974208, 77.592265, 919.0, 3.82f, 4.5f),
        Sample(12.975258, 77.591725, 920.0, 3.63f, 4.3f),
        Sample(12.976247, 77.591680, 922.0, 3.06f, 4.6f),
        Sample(12.977148, 77.592160, 921.0, 3.14f, 4.9f),
    )

    // Keep samples below ChartAccessibility's 25-second signal-gap threshold without inventing a
    // second route. Midpoints preserve the same path, aggregate distance, and elevation profile.
    private val samples = buildList {
        anchors.forEachIndexed { index, sample ->
            add(sample)
            if (index < anchors.lastIndex) add(sample.midpoint(anchors[index + 1]))
        }
    }

    /**
     * Builds a detached value graph. [title] is supplied by the caller so user-facing sample copy
     * remains localized; a null title lets the existing ride-title fallback render normally.
     */
    fun create(
        startTimeMillis: Long = REFERENCE_START_TIME_MILLIS,
        title: String? = null,
        rideId: Long = 0L,
    ): RideWithPoints {
        val ride = RideEntity(
            id = rideId,
            startTime = startTimeMillis,
            endTime = startTimeMillis + DURATION_MILLIS,
            sourceInfo = "TrackMe Onboarding Sample",
            title = title,
            persona = RidePersona.CYCLING.name,
            postRideCalculation = PostRideCalculation(
                maxSpeed = MAX_SPEED_METERS_PER_SECOND,
                distance = DISTANCE_METERS,
                avgSpeed = AVERAGE_SPEED_METERS_PER_SECOND.toFloat(),
                pauseDuration = 0L,
                rawPointCount = POINT_COUNT,
            ),
        )

        val intervalMillis = DURATION_MILLIS / (samples.size - 1)
        val points = samples.mapIndexed { index, sample ->
            GPSPointEntity(
                rideId = rideId,
                latitude = sample.latitude,
                longitude = sample.longitude,
                altitude = sample.altitudeMeters,
                accuracy = sample.accuracyMeters,
                speed = sample.speedMetersPerSecond,
                timestamp = startTimeMillis + intervalMillis * index,
                isPaused = false,
            )
        }

        return RideWithPoints(ride = ride, points = points)
    }
}
