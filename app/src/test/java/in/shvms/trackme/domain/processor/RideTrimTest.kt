package `in`.shvms.trackme.domain.processor

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-253. The window that hides the recording a rider forgot to stop.
 *
 * The risk being tested is asymmetric: leaving a flat tail on a chart is untidy, while trimming a
 * real part of someone's route is data loss they cannot see happening. Most of these cases are
 * about *not* trimming.
 */
class RideTrimTest {

    private val pauseSpeed = 0.5f

    private fun point(second: Long, speed: Float, paused: Boolean = false) = GPSPointEntity(
        rideId = 1L,
        latitude = 12.97 + second * 1e-5,
        longitude = 77.59 + second * 1e-5,
        altitude = 900.0,
        accuracy = 5f,
        speed = speed,
        timestamp = second * 1_000L,
        isPaused = paused,
    )

    /** Stopped for `stillSeconds`, riding for `movingSeconds`, stopped again. */
    private fun ride(leadStill: Int, moving: Int, trailStill: Int): List<GPSPointEntity> {
        val points = mutableListOf<GPSPointEntity>()
        var t = 0L
        repeat(leadStill) { points += point(t++, speed = 0f, paused = true) }
        repeat(moving) { points += point(t++, speed = 6f) }
        repeat(trailStill) { points += point(t++, speed = 0f, paused = true) }
        return points
    }

    @Test
    fun `the forgotten tail is cut`() {
        // The case shvm described: rode 10 minutes, left it recording for 30 after getting home.
        val points = ride(leadStill = 0, moving = 600, trailStill = 1_800)
        val trim = rideTrimWindow(points, pauseSpeed)

        assertTrue(trim.isTrimmed)
        assertEquals(0, trim.startIndex)
        assertEquals(599, trim.endIndex)
        assertEquals(1_800_000L, trim.trailingMillis)
        assertEquals(0L, trim.leadingMillis)
    }

    @Test
    fun `a slow start before setting off is cut too`() {
        val points = ride(leadStill = 300, moving = 600, trailStill = 0)
        val trim = rideTrimWindow(points, pauseSpeed)

        assertEquals(300, trim.startIndex)
        assertEquals(300_000L, trim.leadingMillis)
        assertEquals(0L, trim.trailingMillis)
    }

    @Test
    fun `a pause in the middle of a ride is never cut`() {
        // A traffic light. Cutting an interior stop would teleport the route across the junction,
        // which is a far worse lie than a flat line on a chart.
        val points = buildList {
            repeat(300) { add(point(it.toLong(), 6f)) }
            repeat(300) { add(point(300L + it, 0f, paused = true)) }
            repeat(300) { add(point(600L + it, 6f)) }
        }
        val trim = rideTrimWindow(points, pauseSpeed)

        assertFalse("nothing at either end is stationary", trim.isTrimmed)
        assertEquals(0, trim.startIndex)
        assertEquals(899, trim.endIndex)
    }

    @Test
    fun `a brief wait at the kerb is left alone`() {
        // 30 seconds is a level crossing, not a forgotten recording. Under the minimum run.
        val points = ride(leadStill = 30, moving = 600, trailStill = 30)
        val trim = rideTrimWindow(points, pauseSpeed)

        assertFalse(trim.isTrimmed)
        assertEquals(0, trim.startIndex)
        assertEquals(points.lastIndex, trim.endIndex)
    }

    @Test
    fun `a tail that auto-pause never flagged is still cut on speed alone`() {
        // Auto-pause switched off, or a tail the engine never evaluated: the points are stationary
        // but unflagged, which is why isPaused alone cannot carry this.
        val points = buildList {
            repeat(600) { add(point(it.toLong(), 6f)) }
            repeat(600) { add(point(600L + it, 0.1f, paused = false)) }
        }
        val trim = rideTrimWindow(points, pauseSpeed)

        assertTrue(trim.isTrimmed)
        assertEquals(599, trim.endIndex)
        assertEquals(600_000L, trim.trailingMillis)
    }

    @Test
    fun `a ride that never moved is shown whole rather than blanked`() {
        // Every point stationary. Returning an empty window would draw nothing and read as a bug;
        // showing it all lets the rider see the truth for themselves.
        val points = ride(leadStill = 600, moving = 0, trailStill = 0)
        val trim = rideTrimWindow(points, pauseSpeed)

        assertFalse(trim.isTrimmed)
        assertEquals(0, trim.startIndex)
        assertEquals(points.lastIndex, trim.endIndex)
    }

    @Test
    fun `a very short ride is never trimmed`() {
        val trim = rideTrimWindow(listOf(point(0, 0f, true), point(1, 0f, true)), pauseSpeed)
        assertFalse(trim.isTrimmed)
    }

    @Test
    fun `an empty ride does not throw`() {
        val trim = rideTrimWindow(emptyList(), pauseSpeed)
        assertFalse(trim.isTrimmed)
        assertEquals(0, trim.startIndex)
        assertEquals(0, trim.endIndex)
    }

    @Test
    fun `the window always keeps at least the moving part`() {
        // Property: whatever the ends look like, the returned range must be non-empty and ordered.
        for (lead in listOf(0, 5, 300)) for (trail in listOf(0, 5, 1_800)) {
            val trim = rideTrimWindow(ride(lead, 600, trail), pauseSpeed)
            assertTrue("lead=$lead trail=$trail", trim.endIndex >= trim.startIndex)
        }
    }
}
