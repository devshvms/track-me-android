package `in`.shvms.trackme.data.local

import `in`.shvms.trackme.data.local.dao.HomeDashboardRoutePoint
import `in`.shvms.trackme.data.local.entity.PostRideCalculation
import `in`.shvms.trackme.data.local.entity.RideEntity
import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDashboardMetadataTest {
    private fun completed(
        distance: Double,
        sample: Boolean = false,
        pendingDelete: Boolean = false,
    ) = RideEntity(
        id = 7,
        startTime = 1_000L,
        endTime = 301_000L,
        isSample = sample,
        pendingDelete = pendingDelete,
        postRideCalculation = PostRideCalculation(4f, distance, 2f, 0L),
    )

    // TASK-246 made `routePolyline` required. These cases are about the qualification rule, which
    // does not consult the route shape, so null states plainly that the shape is not under test.
    @Test fun `junk uses AND threshold and is excluded`() {
        assertFalse(withDashboardMetadata(completed(9.0), 119_999L, routePolyline = null, contentHash = null).qualifiesForStats)
        assertTrue(withDashboardMetadata(completed(10.0), 1L, routePolyline = null, contentHash = null).qualifiesForStats)
        assertTrue(withDashboardMetadata(completed(0.0), 120_000L, routePolyline = null, contentHash = null).qualifiesForStats)
    }

    @Test fun `sample and pending deletion never qualify`() {
        assertFalse(withDashboardMetadata(completed(5_000.0, sample = true), 600_000L, routePolyline = null, contentHash = null).qualifiesForStats)
        assertFalse(withDashboardMetadata(completed(5_000.0, pendingDelete = true), 600_000L, routePolyline = null, contentHash = null).qualifiesForStats)
    }

    @Test fun `missing aggregate is versioned but omitted rather than guessed`() {
        val missing = RideEntity(id = 9, startTime = 1_000L, endTime = 301_000L)
        val metadata = withUnavailableDashboardMetadata(missing, pointCount = 1, routePolyline = null)
        assertFalse(metadata.qualifiesForStats)
        assertEquals(HOME_DASHBOARD_METADATA_VERSION, metadata.dashboardMetadataVersion)
        assertEquals(1, metadata.dashboardPointCount)
    }

    @Test fun `point reconciliation uses only positive unpaused intervals`() {
        val points = listOf(
            point(timestamp = 1_000L, paused = false),
            point(timestamp = 11_000L, paused = false),
            point(timestamp = 21_000L, paused = true),
            point(timestamp = 31_000L, paused = false),
            point(timestamp = 41_000L, paused = false),
            point(timestamp = 40_000L, paused = false),
        )
        assertEquals(20_000L, dashboardActiveDurationFromPoints(points))
        assertEquals(null, dashboardActiveDurationFromPoints(points.take(1)))
    }

    @Test fun `canonical metadata persists route availability without a point-table probe`() {
        val metadata = withDashboardMetadata(completed(5_000.0), 300_000L, pointCount = 42, routePolyline = null, contentHash = null)
        assertEquals(42, metadata.dashboardPointCount)
        assertTrue(metadata.qualifiesForStats)
    }

    @Test fun `downsampling is bounded and retains endpoints`() {
        val points = (0 until 1_000).map { HomeDashboardRoutePoint(it.toDouble(), -it.toDouble()) }
        val sampled = HomeDashboardRepository.downsampleRoute(points, 256)
        assertEquals(256, sampled.size)
        assertEquals(points.first(), sampled.first())
        assertEquals(points.last(), sampled.last())
    }

    private fun point(timestamp: Long, paused: Boolean) = GPSPointEntity(
        rideId = 7,
        latitude = 0.0,
        longitude = 0.0,
        altitude = 0.0,
        accuracy = 1f,
        speed = 0f,
        timestamp = timestamp,
        isPaused = paused,
    )
}
