package `in`.shvms.trackme.domain.processor

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.PauseOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseEventsTest {
    @Test
    fun `one through four auto samples each produce exactly one marker`() {
        (1..4).forEach { count ->
            val points = (0 until count).map { point(it, paused = true, origin = PauseOrigin.AUTO) }
            assertEquals("count=$count", 1, autoPauseMarkerLocations(points).size)
        }
    }

    @Test
    fun `separated auto intervals produce independent markers`() {
        val points = listOf(
            point(0, paused = true, origin = PauseOrigin.AUTO),
            point(1, paused = true, origin = PauseOrigin.AUTO),
            point(2),
            point(3, paused = true, origin = PauseOrigin.AUTO),
        )
        assertEquals(2, autoPauseMarkerLocations(points).size)
    }

    @Test
    fun `manual boundaries never produce auto-pause markers`() {
        assertTrue(
            autoPauseMarkerLocations(
                listOf(point(0), point(1, paused = true, origin = PauseOrigin.MANUAL), point(2))
            ).isEmpty()
        )
    }

    @Test
    fun `legacy paused intervals retain compatibility marker without inferred origin`() {
        assertEquals(1, autoPauseMarkerLocations(listOf(point(0, paused = true))).size)
    }

    @Test
    fun `stationary active points and empty input produce no marker`() {
        assertTrue(autoPauseMarkerLocations((0..4).map { point(it, speed = 0f) }).isEmpty())
        assertTrue(autoPauseMarkerLocations(emptyList()).isEmpty())
    }

    private fun point(
        second: Int,
        paused: Boolean = false,
        origin: PauseOrigin? = null,
        speed: Float = 5f,
    ) = GPSPointEntity(
        id = second.toLong(),
        rideId = 1L,
        latitude = 50.0 + second * 0.00001,
        longitude = 8.0,
        altitude = 0.0,
        accuracy = 5f,
        speed = speed,
        timestamp = second * 1_000L,
        isPaused = paused,
        pauseOrigin = origin,
    )
}
