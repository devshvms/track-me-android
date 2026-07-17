package `in`.shvms.trackme.service

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.domain.processor.GeoDistanceCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingSessionRestorerTest {
    @Test
    fun calculatesHudMetricsFromPersistedPoints() {
        val points = listOf(
            point(id = 1L, longitude = 0.000, timestamp = 1_000L, speed = 1f),
            point(id = 2L, longitude = 0.001, timestamp = 2_000L, speed = 2f),
            point(id = 3L, longitude = 0.002, timestamp = 3_000L, speed = 3f)
        )

        val metrics = TrackingSessionRestorer.calculate(
            rideStartTime = 1_000L,
            points = points,
            nowMillis = 8_000L,
            distanceCalculator = testDistance
        )

        assertTrue(metrics.distanceMeters > 200f)
        assertEquals(2_000L, metrics.activeDurationMillis)
        assertEquals(7_000L, metrics.elapsedDurationMillis)
        assertEquals(3f, metrics.latestSpeedMetersPerSecond, 0.001f)
        assertEquals(false, metrics.isPaused)
    }

    @Test
    fun excludesPausedSegmentsFromDistanceAndActiveDuration() {
        val points = listOf(
            point(id = 1L, longitude = 0.000, timestamp = 1_000L, speed = 1f),
            point(id = 2L, longitude = 0.001, timestamp = 2_000L, speed = 2f, isPaused = true),
            point(id = 3L, longitude = 0.002, timestamp = 3_000L, speed = 3f, isPaused = true)
        )

        val metrics = TrackingSessionRestorer.calculate(
            rideStartTime = 1_000L,
            points = points,
            nowMillis = 3_000L,
            distanceCalculator = testDistance
        )

        assertEquals(0f, metrics.distanceMeters, 0.001f)
        assertEquals(0L, metrics.activeDurationMillis)
        assertEquals(true, metrics.isPaused)
    }

    private fun point(
        id: Long,
        longitude: Double,
        timestamp: Long,
        speed: Float,
        isPaused: Boolean = false
    ) = GPSPointEntity(
        id = id,
        rideId = 7L,
        latitude = 0.0,
        longitude = longitude,
        altitude = 0.0,
        accuracy = 5f,
        speed = speed,
        timestamp = timestamp,
        isPaused = isPaused
    )

    private val testDistance = GeoDistanceCalculator { from, to ->
        kotlin.math.abs(to.longitude - from.longitude).toFloat() * 100_000f
    }
}
