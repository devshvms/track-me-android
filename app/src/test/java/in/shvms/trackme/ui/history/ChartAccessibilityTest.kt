package `in`.shvms.trackme.ui.history

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartAccessibilityTest {
    @Test
    fun chartSummaryDescribesMetricsAndGpsGaps() {
        val points = listOf(
            point(timestamp = 1_000L, speed = 2f, altitude = 100.0),
            point(timestamp = 2_000L, speed = 4f, altitude = 120.0),
            point(timestamp = 30_000L, speed = 6f, altitude = 110.0)
        )

        val summary = buildChartAccessibilityDescription(points)

        assertTrue(summary.contains("Speed and altitude chart"))
        assertTrue(summary.contains("Average speed 14.4 km/h"))
        assertTrue(summary.contains("Altitude from 100 to 120 meters"))
        assertTrue(summary.contains("1 GPS signal gap"))
    }

    @Test
    fun emptyChartSummaryExplainsMissingData() {
        assertTrue(
            buildChartAccessibilityDescription(emptyList()).contains("No GPS data available")
        )
    }

    private fun point(timestamp: Long, speed: Float, altitude: Double) = GPSPointEntity(
        rideId = 1L,
        latitude = 0.0,
        longitude = 0.0,
        altitude = altitude,
        accuracy = 5f,
        speed = speed,
        timestamp = timestamp,
        isPaused = false
    )
}
