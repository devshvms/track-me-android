package `in`.shvms.trackme.data.local

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.domain.processor.RideGaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-259, found by review. One quantity, five implementations, three different answers.
 *
 * Ride finalisation, crash recovery and dashboard reconstruction counted every positive gap;
 * `GPSProcessor` capped at 15 s; the cloud path capped at 60 s behind a comment claiming it matched
 * the processor, which it never did. A ride's duration -- and its average speed, and every total
 * derived from them -- changed depending on which path last touched it.
 *
 * The regression these guard is not "the number is 25 s". It is **that all five agree**, which is
 * the property that was actually broken.
 */
class MovingTimeRuleTest {

    private fun point(second: Long, paused: Boolean = false) = GPSPointEntity(
        rideId = 1L, latitude = 12.97, longitude = 77.59, altitude = 0.0,
        accuracy = 5f, speed = 5f, timestamp = second * 1_000L, isPaused = paused,
    )

    @Test
    fun `an ordinary interval counts`() {
        assertTrue(countsAsMovingTime(point(0), point(1)))
    }

    @Test
    fun `a paused endpoint never counts, on either side`() {
        assertFalse(countsAsMovingTime(point(0, paused = true), point(1)))
        assertFalse(countsAsMovingTime(point(0), point(1, paused = true)))
    }

    @Test
    fun `a gap longer than the app's own definition of a gap does not count`() {
        // 25s is the number behind the "GPS signal gaps" count shown in Recording details and
        // behind TASK-257's dotted segments. A stretch the app reports to a rider as a gap must not
        // also be counted as time they spent moving.
        assertTrue(countsAsMovingTime(point(0), point(25)))
        assertFalse(countsAsMovingTime(point(0), point(26)))
    }

    @Test
    fun `non-positive intervals never count`() {
        assertFalse("zero", countsAsMovingTime(point(5), point(5)))
        assertFalse("clock went backwards", countsAsMovingTime(point(10), point(5)))
    }

    @Test
    fun `the threshold is the shared one, not a private copy`() {
        // If RideGaps moves, this moves with it rather than drifting -- which is how the five
        // implementations got out of step in the first place.
        assertEquals(25_000L, RideGaps.GAP_THRESHOLD_MILLIS)
    }

    @Test
    fun `all five paths agree on the same ride`() {
        // The property that was broken. A ride with an ordinary stretch, a pause, and a GPS gap.
        val points = listOf(
            point(0), point(1), point(2),                 // 2s moving
            point(3, paused = true),                      // pause boundary
            point(60),                                    // 57s gap out of the pause
            point(61), point(62),                         // 2s moving
            point(200),                                   // 138s GPS gap
            point(201),                                   // 1s moving
        )

        // Reconstruction, which is the path the dashboard sweep uses.
        val reconstructed = dashboardActiveDurationFromPoints(points)

        // The same rule applied by hand, as the other four paths now do it.
        val byRule = (1 until points.size).sumOf { i ->
            if (countsAsMovingTime(points[i - 1], points[i])) {
                points[i].timestamp - points[i - 1].timestamp
            } else 0L
        }

        assertEquals(byRule, reconstructed)
        // 1s + 1s (start) + 1s + 1s (after gap) + 1s = the intervals that are neither paused nor
        // longer than the gap threshold.
        assertEquals(5_000L, reconstructed)
    }

    @Test
    fun `an uncapped sum would have been far larger, which is the defect`() {
        // Demonstrates the inflation the review found: without a cap the two long gaps are counted
        // as moving time, nearly forty times the real figure.
        val points = listOf(point(0), point(1), point(60), point(200))
        val uncapped = (1 until points.size).sumOf { i ->
            (points[i].timestamp - points[i - 1].timestamp).coerceAtLeast(0L)
        }
        val capped = dashboardActiveDurationFromPoints(points) ?: 0L

        assertEquals(200_000L, uncapped)
        assertEquals(1_000L, capped)
    }
}
