package `in`.shvms.trackme.domain.replay

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayExportStatsTest {
    private val points = listOf(
        point(latitude = 0.0, longitude = 0.0, timestamp = 1_000L),
        point(latitude = 0.0, longitude = 0.001, timestamp = 11_000L),
        point(latitude = 0.0, longitude = 0.002, timestamp = 21_000L)
    )

    @Test
    fun `stats start at zero and increase with frame progress`() {
        val fallback = ReplayStats(distanceMeters = 999.0, durationMillis = 99_000L, averageSpeedMetersPerSecond = 1.0)

        val start = replayStatsAtProgress(points, progress = 0f, fallback = fallback)
        val middle = replayStatsAtProgress(points, progress = 0.5f, fallback = fallback)
        val end = replayStatsAtProgress(points, progress = 1f, fallback = fallback)

        assertEquals(0.0, start.distanceMeters, 0.001)
        assertEquals(0L, start.durationMillis)
        assertTrue(middle.distanceMeters > start.distanceMeters)
        assertTrue(middle.distanceMeters < end.distanceMeters)
        assertEquals(20_000L, end.durationMillis)
        assertTrue(end.averageSpeedMetersPerSecond > 0.0)
    }

    @Test
    fun `insufficient route points preserve exporter fallback`() {
        val fallback = ReplayStats(distanceMeters = 42.0, durationMillis = 7_000L, averageSpeedMetersPerSecond = 6.0)
        val result = replayStatsAtProgress(points.take(1), progress = 0.5f, fallback = fallback)

        assertEquals(fallback, result)
    }

    private fun point(latitude: Double, longitude: Double, timestamp: Long) = GPSPointEntity(
        rideId = 1L,
        latitude = latitude,
        longitude = longitude,
        altitude = 0.0,
        accuracy = 1f,
        speed = 0f,
        timestamp = timestamp,
        isPaused = false
    )
}
