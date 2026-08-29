package `in`.shvms.trackme.ui.history

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PausedMarkerModelTest {

    @Test
    fun `one, two, three and four explicit paused samples each produce one marker`() {
        fun testSize(count: Int) {
            val points = (0 until count).map { index -> point(index, 50.0 + index * 0.00001, 8.0, isPaused = true) }
            val markers = explicitPauseMarkerLocations(points)
            assertEquals("Failed for count $count", 1, markers.size)
        }
        
        testSize(1)
        testSize(2)
        testSize(3)
        testSize(4)
    }

    @Test
    fun `two explicit pause intervals separated by an active sample produce two markers`() {
        val points = buildList {
            add(point(1, 50.0, 8.0, isPaused = true))
            add(point(2, 50.0, 8.0, isPaused = true))
            
            add(point(3, 50.1, 8.0, isPaused = false))
            
            add(point(4, 50.2, 8.0, isPaused = true))
            add(point(5, 50.2, 8.0, isPaused = true))
        }

        val markers = explicitPauseMarkerLocations(points)
        assertEquals(2, markers.size)
    }

    @Test
    fun `slow or stationary unpaused samples produce no pause marker`() {
        val points = (0 until 10).map { index -> point(index, 50.0, 8.0, isPaused = false, speed = 0f) }
        assertTrue(explicitPauseMarkerLocations(points).isEmpty())
    }

    @Test
    fun `empty inputs do not crash and return empty`() {
        assertTrue(explicitPauseMarkerLocations(emptyList()).isEmpty())
    }

    @Test
    fun `all-paused input produces exactly one marker`() {
        val points = (0 until 10).map { index -> point(index, 50.0 + index * 0.0001, 8.0, isPaused = true) }
        val markers = explicitPauseMarkerLocations(points)
        assertEquals(1, markers.size)
    }

    private fun point(id: Int, latitude: Double, longitude: Double, isPaused: Boolean = false, speed: Float = 5f) = GPSPointEntity(
        id = id.toLong(),
        rideId = 1L,
        latitude = latitude,
        longitude = longitude,
        altitude = 0.0,
        accuracy = 5f,
        speed = speed,
        timestamp = id.toLong() * 1000,
        isPaused = isPaused
    )
}
