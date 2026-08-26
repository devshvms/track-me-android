package `in`.shvms.trackme.domain.processor

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ElevationGainTest {
    @Test
    fun `fewer than ten valid altitude points have no elevation gain`() {
        assertNull(calculateElevationGainMeters(points((1..4).flatMap { listOf(0.0, 100.0) } + listOf(0.0))))
    }

    @Test
    fun `noisy flat route stays flat`() {
        val noisyFlat = listOf(100.0, 100.8, 99.7, 100.6, 99.9, 100.5, 99.6, 100.7, 99.8, 100.4)

        assertEquals(0.0, calculateElevationGainMeters(points(noisyFlat)) ?: -1.0, 0.001)
    }

    @Test
    fun `smoothed climb accumulates ascent and ignores descent`() {
        val climbWithDescent = listOf(0.0, 0.0, 0.0, 0.0, 0.0, 100.0, 100.0, 100.0, 100.0, 100.0)

        assertEquals(100.0, calculateElevationGainMeters(points(climbWithDescent)) ?: -1.0, 0.001)
    }

    private fun points(altitudes: List<Double>) = altitudes.mapIndexed { index, altitude ->
        GPSPointEntity(
            id = index.toLong(),
            rideId = 1L,
            latitude = 0.0,
            longitude = index / 10_000.0,
            altitude = altitude,
            accuracy = 5f,
            speed = 1f,
            timestamp = index * 1_000L,
            isPaused = false,
        )
    }
}
