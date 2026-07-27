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
        assertTrue(middle.durationMillis > start.durationMillis)
        assertTrue(middle.durationMillis < end.durationMillis)
        assertTrue(end.averageSpeedMetersPerSecond > 0.0)
    }

    /**
     * The regression this task exists for: the rendered route is privacy-trimmed, so summing its
     * geometry left the last frame short of the ride's real totals and the shared video
     * contradicted the History card. The final frame must land exactly on the stored values.
     */
    @Test
    fun `final frame matches the ride totals shown in the app`() {
        val fallback = ReplayStats(distanceMeters = 12_345.6, durationMillis = 3_600_000L, averageSpeedMetersPerSecond = 3.4)

        val end = replayStatsAtProgress(points, progress = 1f, fallback = fallback)

        assertEquals(fallback.distanceMeters, end.distanceMeters, 0.001)
        assertEquals(fallback.durationMillis, end.durationMillis)
    }

    @Test
    fun `stats never exceed the ride totals when progress overshoots`() {
        val fallback = ReplayStats(distanceMeters = 500.0, durationMillis = 60_000L, averageSpeedMetersPerSecond = 8.3)

        val overshoot = replayStatsAtProgress(points, progress = 3f, fallback = fallback)

        assertEquals(500.0, overshoot.distanceMeters, 0.001)
        assertEquals(60_000L, overshoot.durationMillis)
    }

    /** Legacy imports and recovered orphans have no stored calculation — render geometry, not 0. */
    @Test
    fun `rides without stored totals fall back to route geometry`() {
        val fallback = ReplayStats(distanceMeters = 0.0, durationMillis = 0L, averageSpeedMetersPerSecond = 0.0)

        val end = replayStatsAtProgress(points, progress = 1f, fallback = fallback)

        assertEquals(geometricRouteDistanceMeters(points), end.distanceMeters, 0.001)
        assertEquals(20_000L, end.durationMillis)
        assertTrue(end.distanceMeters > 0.0)
    }

    /**
     * Distance advances by distance and duration by elapsed time, so a long mid-route stop moves
     * the clock without moving the odometer.
     */
    @Test
    fun `a mid-route stop advances duration without advancing distance`() {
        val stopped = listOf(
            point(latitude = 0.0, longitude = 0.0, timestamp = 0L),
            point(latitude = 0.0, longitude = 0.001, timestamp = 10_000L),
            point(latitude = 0.0, longitude = 0.001, timestamp = 610_000L)
        )

        // Progress 0.5 lands on the end of the moving segment: all of the distance, none of the stop.
        val midpoint = replayStatsAtProgress(
            stopped,
            progress = 0.5f,
            fallback = ReplayStats(1_000.0, 610_000L, 0.0)
        )

        assertEquals(1.0, routeDistanceFraction(stopped, progress = 0.5f), 0.0001)
        assertEquals(1_000.0, midpoint.distanceMeters, 0.001)
        assertEquals(10_000L, midpoint.durationMillis)
        assertTrue(midpoint.durationMillis < 610_000L / 2)
    }

    @Test
    fun `insufficient route points preserve exporter fallback`() {
        val fallback = ReplayStats(distanceMeters = 42.0, durationMillis = 7_000L, averageSpeedMetersPerSecond = 6.0)
        val result = replayStatsAtProgress(points.take(1), progress = 0.5f, fallback = fallback)

        assertEquals(fallback, result)
    }

    @Test
    fun `map projection follows center crop used for snapshot`() {
        val center = mapProjectionToFrame(
            normalized = 0.5f to 0.5f,
            snapshotWidth = 540,
            snapshotHeight = 960,
            frameWidth = 1080f,
            frameHeight = 1080f
        )

        assertEquals(540f, center.first, 0.001f)
        assertEquals(540f, center.second, 0.001f)
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
