package `in`.shvms.trackme.ui.onboarding

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.PostRideCalculation
import `in`.shvms.trackme.data.local.entity.RideEntity
import `in`.shvms.trackme.data.local.entity.RideWithPoints
import `in`.shvms.trackme.domain.model.RidePersona

/**
 * Canonical ride used by the onboarding demos and the first-run sample ride.
 *
 * The route is now a **real recording** rather than a synthetic drawing: a Cycling ride captured on
 * the iOS Simulator against Apple's "City Bicycle Ride" location scenario through Cupertino, read
 * from `demo_ride.gpx`. The same file ships on iOS, so both platforms show the identical route.
 *
 * Two honest limitations of that recording, both visible in the demo:
 *
 *  - **There is no elevation.** The scenario supplies no terrain, so every point sits at 0 m and the
 *    elevation trace renders flat. Both chart implementations already guard a zero altitude range
 *    (`rawMax > rawMin ? … : 1`), so this degrades rather than divides by zero. Speed is real and
 *    varied, so the chart still carries information.
 *  - **The scenario loops every 15.6 minutes**, so the back half of the ride retraces the front
 *    half. Real enough — cyclists do laps — but the map trail overlaps itself.
 *
 * Everything is still constructed as plain values, so showing a demo can never read or write Room.
 */
object OnboardingDemoFixture {
    const val REFERENCE_START_TIME_MILLIS = 1_767_225_600_000L

    private val track get() = DemoRideGpx.track

    /** Recorded wall-clock span of the ride. */
    val DURATION_MILLIS: Long get() = track.durationMillis
    val DISTANCE_METERS: Double get() = track.distanceMeters
    val AVERAGE_SPEED_METERS_PER_SECOND: Double get() = track.averageSpeedMetersPerSecond
    val MAX_SPEED_METERS_PER_SECOND: Float get() = track.maxSpeedMetersPerSecond
    val POINT_COUNT: Int get() = track.points.size

    /**
     * Builds a detached value graph. [title] is supplied by the caller so user-facing sample copy
     * remains localized; a null title lets the existing ride-title fallback render normally.
     *
     * Point timestamps are rebased onto [startTimeMillis] using each fix's recorded offset, so the
     * real cadence — including the pauses at junctions — survives being replayed at any date.
     */
    fun create(
        startTimeMillis: Long = REFERENCE_START_TIME_MILLIS,
        title: String? = null,
        rideId: Long = 0L,
    ): RideWithPoints {
        val source = track
        val ride = RideEntity(
            id = rideId,
            startTime = startTimeMillis,
            endTime = startTimeMillis + source.durationMillis,
            sourceInfo = "TrackMe Onboarding Sample",
            title = title,
            persona = RidePersona.CYCLING.name,
            postRideCalculation = PostRideCalculation(
                maxSpeed = source.maxSpeedMetersPerSecond,
                distance = source.distanceMeters,
                avgSpeed = source.averageSpeedMetersPerSecond.toFloat(),
                pauseDuration = 0L,
                rawPointCount = source.points.size,
            ),
        )

        val points = source.points.map { point ->
            GPSPointEntity(
                rideId = rideId,
                latitude = point.latitude,
                longitude = point.longitude,
                altitude = point.altitudeMeters,
                accuracy = point.accuracyMeters,
                speed = point.speedMetersPerSecond,
                timestamp = startTimeMillis + point.offsetMillis,
                isPaused = false,
            )
        }

        return RideWithPoints(ride = ride, points = points)
    }
}
