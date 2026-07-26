package `in`.shvms.trackme.ui.history

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PausedMarkerModelTest {
    @Test
    fun `nearby sustained slow samples collapse to one marker`() {
        val points = (0 until 8).map { index -> point(index, 50.0 + index * 0.00001, 8.0) }

        val markers = pausedMarkerLocations(points)

        assertEquals(1, markers.size)
        assertEquals(50.000035, markers.single().latitude, 0.000001)
    }

    @Test
    fun `brief slowdowns do not create markers`() {
        val points = (0 until 3).map { index -> point(index, 50.0 + index * 0.00001, 8.0) }

        assertTrue(pausedMarkerLocations(points).isEmpty())
    }

    @Test
    fun `many sustained stops are capped while preserving chronological order`() {
        val points = buildList {
            repeat(5) { stop ->
                repeat(4) { sample ->
                    add(point(stop * 10 + sample, 50.0 + stop * 0.001 + sample * 0.00001, 8.0))
                }
            }
        }

        val markers = pausedMarkerLocations(points)

        assertEquals(PAUSED_MARKER_MAX_COUNT, markers.size)
        assertTrue(markers.zipWithNext().all { (first, second) -> first.latitude < second.latitude })
    }

    private fun point(id: Int, latitude: Double, longitude: Double) = GPSPointEntity(
        id = id.toLong(),
        rideId = 1L,
        latitude = latitude,
        longitude = longitude,
        altitude = 0.0,
        accuracy = 5f,
        speed = 0f,
        timestamp = id.toLong(),
        isPaused = false
    )
}
