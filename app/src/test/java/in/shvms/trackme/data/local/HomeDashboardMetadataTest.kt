package `in`.shvms.trackme.data.local

import `in`.shvms.trackme.data.local.dao.HomeDashboardRoutePoint
import `in`.shvms.trackme.data.local.entity.PostRideCalculation
import `in`.shvms.trackme.data.local.entity.RideEntity
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

    @Test fun `junk uses AND threshold and is excluded`() {
        assertFalse(withDashboardMetadata(completed(9.0), 119_999L).qualifiesForStats)
        assertTrue(withDashboardMetadata(completed(10.0), 1L).qualifiesForStats)
        assertTrue(withDashboardMetadata(completed(0.0), 120_000L).qualifiesForStats)
    }

    @Test fun `sample and pending deletion never qualify`() {
        assertFalse(withDashboardMetadata(completed(5_000.0, sample = true), 600_000L).qualifiesForStats)
        assertFalse(withDashboardMetadata(completed(5_000.0, pendingDelete = true), 600_000L).qualifiesForStats)
    }

    @Test fun `missing aggregate is versioned but omitted rather than guessed`() {
        val missing = RideEntity(id = 9, startTime = 1_000L, endTime = 301_000L)
        val metadata = withDashboardMetadata(missing, 300_000L)
        assertFalse(metadata.qualifiesForStats)
        assertEquals(HOME_DASHBOARD_METADATA_VERSION, metadata.dashboardMetadataVersion)
    }

    @Test fun `downsampling is bounded and retains endpoints`() {
        val points = (0 until 1_000).map { HomeDashboardRoutePoint(it.toDouble(), -it.toDouble()) }
        val sampled = HomeDashboardRepository.downsampleRoute(points, 256)
        assertEquals(256, sampled.size)
        assertEquals(points.first(), sampled.first())
        assertEquals(points.last(), sampled.last())
    }
}
