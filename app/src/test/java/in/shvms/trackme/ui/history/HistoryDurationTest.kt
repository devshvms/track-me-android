package `in`.shvms.trackme.ui.history

import `in`.shvms.trackme.data.local.HOME_DASHBOARD_METADATA_VERSION
import `in`.shvms.trackme.data.local.entity.PostRideCalculation
import `in`.shvms.trackme.data.local.entity.RideEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryDurationTest {
    @Test
    fun `history uses reconciled active duration and keeps average speed consistent`() {
        val distanceMeters = 5_000.0
        val activeDurationMillis = 300_000L
        val averageSpeedMps = distanceMeters / (activeDurationMillis / 1_000.0)
        val ride = RideEntity(
            startTime = 1_000L,
            endTime = 1_201_000L,
            dashboardActiveDurationMillis = activeDurationMillis,
            dashboardMetadataVersion = HOME_DASHBOARD_METADATA_VERSION,
            postRideCalculation = PostRideCalculation(
                maxSpeed = 8f,
                distance = distanceMeters,
                avgSpeed = averageSpeedMps.toFloat(),
                pauseDuration = 901_000L,
            ),
        )

        assertEquals(activeDurationMillis, displayActiveDurationMillis(ride))
        assertEquals(
            distanceMeters / (displayActiveDurationMillis(ride)!! / 1_000.0),
            ride.postRideCalculation!!.avgSpeed.toDouble(),
            0.0001,
        )
    }

    @Test
    fun `legacy history does not guess duration from wall time`() {
        val ride = RideEntity(
            startTime = 1_000L,
            endTime = 1_201_000L,
            dashboardActiveDurationMillis = 0L,
            dashboardMetadataVersion = 0,
        )

        assertNull(displayActiveDurationMillis(ride))
        assertNull(displayExportDuration(ride))
    }

    /**
     * TASK-230. The rider is taught mid-ride that moving and total are different numbers; the pair
     * has to survive the ride ending, and a ride that never paused has to show them equal rather
     * than suppress one -- suppression is what made a single unlabelled figure ambiguous.
     */
    @Test
    fun `a paused ride shows moving and total as a differing pair`() {
        val ride = RideEntity(
            startTime = 1_000L,
            endTime = 1_201_000L,
            dashboardActiveDurationMillis = 300_000L,
            dashboardMetadataVersion = HOME_DASHBOARD_METADATA_VERSION,
        )

        assertEquals(300_000L, displayActiveDurationMillis(ride))
        assertEquals(1_200_000L, displayTotalElapsedMillis(ride))
    }

    @Test
    fun `a ride with no pause shows both figures equal rather than suppressing one`() {
        val ride = RideEntity(
            startTime = 1_000L,
            endTime = 301_000L,
            dashboardActiveDurationMillis = 300_000L,
            dashboardMetadataVersion = HOME_DASHBOARD_METADATA_VERSION,
        )

        assertEquals(displayActiveDurationMillis(ride), displayTotalElapsedMillis(ride))
    }

    @Test
    fun `an unreconciled ride still reads unknown for moving time, never a fake zero`() {
        val ride = RideEntity(
            startTime = 1_000L,
            endTime = 1_201_000L,
            dashboardActiveDurationMillis = 0L,
            dashboardMetadataVersion = 0,
        )

        assertNull(displayActiveDurationMillis(ride))
        // Total elapsed needs no reconciliation, so the rider still gets one real number.
        assertEquals(1_200_000L, displayTotalElapsedMillis(ride))
    }

    @Test
    fun `a ride with no usable end has no total`() {
        assertNull(displayTotalElapsedMillis(RideEntity(startTime = 1_000L, endTime = null)))
        assertNull(displayTotalElapsedMillis(RideEntity(startTime = 1_000L, endTime = 0L)))
        assertNull(displayTotalElapsedMillis(RideEntity(startTime = 1_000L, endTime = 1_000L)))
    }

    @Test
    fun `export duration uses active time rather than wall time`() {
        val ride = RideEntity(
            startTime = 1_000L,
            endTime = 1_201_000L,
            dashboardActiveDurationMillis = 300_000L,
            dashboardMetadataVersion = HOME_DASHBOARD_METADATA_VERSION,
        )

        assertEquals("5min", displayExportDuration(ride))
    }
}
