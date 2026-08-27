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

    /**
     * The case both original vectors missed, and the reason this shipped returning 0 for every
     * real ride: a climb gentle enough that no *pair of consecutive samples* clears the noise
     * floor. 100 m over 600 samples is 1 Hz logging on a ten-minute climb — utterly ordinary, and
     * about 0.17 m per sample. The old sample-to-sample floor discarded every one of them.
     *
     * Tolerance is one noise floor, and that is a real bound rather than slack: hysteresis banks a
     * climb only once it clears the threshold, so whatever is still unbanked when the ride ends —
     * always less than 2 m — is never counted. Reported here is 98.2 of a true 100.
     */
    @Test
    fun `a gradual real-world climb is not discarded by the noise floor`() {
        val gradual = (0..599).map { it * (100.0 / 599) }

        assertEquals(100.0, calculateElevationGainMeters(points(gradual)) ?: -1.0, 2.0)
    }

    @Test
    fun `jitter riding on top of a gradual climb still reports the climb, not the jitter`() {
        val jittery = (0..599).map { index ->
            index * (100.0 / 599) + if (index % 2 == 0) 0.6 else -0.6
        }

        assertEquals(100.0, calculateElevationGainMeters(points(jittery)) ?: -1.0, 2.0)
    }

    @Test
    fun `a descent and re-ascent of the same hill counts the climb twice, not once`() {
        // Total ascent, not net: down 50 then up 50 is 50 m of climbing on the way back up.
        val upDownUp = (0..199).map { 50.0 } +
            (0..199).map { 50.0 - it * (50.0 / 199) } +
            (0..199).map { it * (50.0 / 199) }

        assertEquals(50.0, calculateElevationGainMeters(points(upDownUp)) ?: -1.0, 2.0)
    }

    @Test
    fun `a flat ride reports zero rather than null`() {
        // Zero here is a fact — the ride was flat — and is distinct from "no altitude data", which
        // is null and renders no cell at all (§5.2).
        assertEquals(0.0, calculateElevationGainMeters(points(List(50) { 800.0 })) ?: -1.0, 0.001)
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
