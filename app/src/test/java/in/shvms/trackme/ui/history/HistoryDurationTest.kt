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
        assertEquals("Unknown", displayExportDuration(ride, "Unknown"))
    }

    @Test
    fun `export duration uses active time rather than wall time`() {
        val ride = RideEntity(
            startTime = 1_000L,
            endTime = 1_201_000L,
            dashboardActiveDurationMillis = 300_000L,
            dashboardMetadataVersion = HOME_DASHBOARD_METADATA_VERSION,
        )

        assertEquals("5min", displayExportDuration(ride, "Unknown"))
    }
}
