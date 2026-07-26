package `in`.shvms.trackme.domain.export

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportPrivacyTrimTest {
    private fun point(index: Int) = GPSPointEntity(
        id = index.toLong(),
        rideId = 7L,
        latitude = index.toDouble(),
        longitude = index.toDouble(),
        altitude = 0.0,
        accuracy = 1f,
        speed = 1f,
        timestamp = index.toLong(),
        isPaused = false
    )

    @Test
    fun trimsPresentationRouteFromBothEnds() {
        val points = (0..5).map(::point)
        val trimmed = trimGpsPointsForExport(points, trimMeters = 200.0) { _, _ -> 100.0 }

        assertEquals(listOf(points[2], points[3]), trimmed)
    }

    @Test
    fun shortRouteRemainsAvailableForGracefulExport() {
        val points = listOf(point(0), point(1), point(2))

        assertEquals(points, trimGpsPointsForExport(points, trimMeters = 200.0) { _, _ -> 100.0 })
        assertEquals(points, trimGpsPointsForExport(points, trimMeters = 0.0) { _, _ -> 100.0 })
    }

    @Test
    fun staticMapDimensionsPreserveStoryRatioWithinApiLimit() {
        assertEquals(Pair(360, 640), staticMapRequestDimensions(9, 16))
        assertEquals(Pair(640, 640), staticMapRequestDimensions(1, 1))
    }
}
