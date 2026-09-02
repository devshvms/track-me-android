package `in`.shvms.trackme.domain.processor

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.PauseOrigin
import `in`.shvms.trackme.domain.model.RidePersona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-257. Whether the straight line between two fixes represents a stretch that was never
 * recorded.
 *
 * The two failure directions are not symmetric, and most of these cases guard the dangerous one: a
 * false negative leaves a cosmetically solid line and a slightly generous distance, while a false
 * positive **silently deletes real distance from a rider's own ride**.
 */
class RideGapsTest {

    private fun point(
        second: Long,
        lat: Double,
        lon: Double,
        paused: Boolean = false,
        pauseOrigin: PauseOrigin? = null,
    ) =
        GPSPointEntity(
            rideId = 1L, latitude = lat, longitude = lon, altitude = 0.0,
            accuracy = 5f, speed = 0f, timestamp = second * 1_000L, isPaused = paused,
            pauseOrigin = pauseOrigin,
        )

    /** ~111 m per 0.001 degree of latitude. */
    private fun northOf(base: GPSPointEntity, metres: Double, afterSeconds: Long) =
        point(base.timestamp / 1000 + afterSeconds, base.latitude + metres / 111_320.0, base.longitude)

    @Test
    fun `a walk across a manual pause is NOT caught by the speed rule, and must not be`() {
        // shvm's screenshot: paused, moved ~600 m over four minutes. That is 9 km/h -- an ordinary
        // walking pace, indistinguishable by speed from walking while recording.
        //
        // This is the limit of the persona rule and the reason the manual pause now writes a
        // flagged point instead: only knowing *that they paused* can catch this. Asserting the
        // negative here keeps that reasoning attached to the code, so nobody later "fixes" the
        // ceiling downward to catch it and starts discarding real walking segments.
        val a = point(0, 12.9700, 77.5900)
        val b = northOf(a, metres = 600.0, afterSeconds = 240)
        assertFalse(RideGaps.isUnrecordedGap(a, b, RidePersona.WALK))
    }

    @Test
    fun `a teleport across a pause is caught`() {
        // The half the speed rule does catch: same four minutes, but 6 km covered -- 90 km/h, which
        // no walk produces. The straight line is certainly not the path taken.
        val a = point(0, 12.9700, 77.5900)
        val b = northOf(a, metres = 6_000.0, afterSeconds = 240)
        assertTrue(RideGaps.isUnrecordedGap(a, b, RidePersona.WALK))
    }

    @Test
    fun `a sparse but ordinary stretch is NOT a gap`() {
        // 30 seconds between fixes at a plausible cycling speed. Time alone would have discarded
        // this and undercounted a stretch the rider really did travel -- the reason the rule needs
        // both signals.
        val a = point(0, 12.9700, 77.5900)
        val b = northOf(a, metres = 150.0, afterSeconds = 30) // 18 km/h
        assertFalse(RideGaps.isUnrecordedGap(a, b, RidePersona.CYCLING))
    }

    @Test
    fun `a single jittery fix is NOT a gap, however absurd its implied speed`() {
        // One bad fix at 1 Hz can imply hundreds of km/h. Without the time-gap requirement this
        // would discard a real segment. This is why the rule is AND, never OR.
        val a = point(0, 12.9700, 77.5900)
        val b = northOf(a, metres = 400.0, afterSeconds = 1) // 1440 km/h
        assertFalse("no time gap, so not a gap", RideGaps.isUnrecordedGap(a, b, RidePersona.WALK))
    }

    @Test
    fun `the ceiling is persona-aware`() {
        // 60 seconds, 1 km: 60 km/h. Beyond a walk, ordinary in a car.
        val a = point(0, 12.9700, 77.5900)
        val b = northOf(a, metres = 1_000.0, afterSeconds = 60)
        assertTrue(RideGaps.isUnrecordedGap(a, b, RidePersona.WALK))
        assertFalse(RideGaps.isUnrecordedGap(a, b, RidePersona.CAR_DRIVE))
    }

    @Test
    fun `AUTO takes the most permissive ceiling`() {
        // An unknown activity must never be the reason a real segment is discarded.
        assertEquals(
            RideGaps.maxPlausibleSpeedMps(RidePersona.CAR_DRIVE),
            RideGaps.maxPlausibleSpeedMps(RidePersona.AUTO),
        )
    }

    @Test
    fun `a long stop that did not move is not a gap`() {
        // Waiting 10 minutes at the same spot: a big time gap, no implied speed. There is nothing
        // to dot and nothing to discard -- the distance is zero either way.
        val a = point(0, 12.9700, 77.5900)
        val b = point(600, 12.9700, 77.5900)
        assertFalse(RideGaps.isUnrecordedGap(a, b, RidePersona.WALK))
    }

    @Test
    fun `runs split at gaps and nowhere else`() {
        val p0 = point(0, 12.9700, 77.5900)
        val p1 = northOf(p0, 20.0, 5)
        val p2 = northOf(p1, 20.0, 5)
        val far = northOf(p2, 3_000.0, 120)      // gap: 90 km/h on a walk
        val p4 = northOf(far, 20.0, 5)

        val runs = RideGaps.recordedRuns(listOf(p0, p1, p2, far, p4), RidePersona.WALK)

        assertEquals(2, runs.size)
        assertEquals(3, runs[0].size)
        assertEquals(2, runs[1].size)
    }

    @Test
    fun `auto pause markers do not split solid runs`() {
        val p0 = point(0, 12.9700, 77.5900)
        val p1 = northOf(p0, 20.0, 5)
        val paused = point(10, p1.latitude, p1.longitude, paused = true)
        val resumed = northOf(paused, 60.0, 240) // within plausible speed
        val p4 = northOf(resumed, 20.0, 5)

        val runs = RideGaps.recordedRuns(listOf(p0, p1, paused, resumed, p4), RidePersona.WALK)

        // It should NOT split into two runs. It is one contiguous solid run.
        assertEquals(1, runs.size)
        assertEquals(5, runs[0].size)
    }

    @Test
    fun `manual pause authority splits after its recorded boundary`() {
        val before = point(0, 12.9700, 77.5900)
        val boundary = point(1, 12.9701, 77.5900, paused = true, pauseOrigin = PauseOrigin.MANUAL)
        val resumed = point(240, 12.9755, 77.5900)

        val runs = RideGaps.recordedRuns(listOf(before, boundary, resumed), RidePersona.WALK)

        assertEquals(2, runs.size)
        assertEquals(listOf(before, boundary), runs[0])
        assertEquals(listOf(resumed), runs[1])
    }

    @Test
    fun `a ride with no gaps stays one run`() {
        val p0 = point(0, 12.9700, 77.5900)
        val points = listOf(p0, northOf(p0, 20.0, 5), northOf(p0, 40.0, 10))
        assertEquals(1, RideGaps.recordedRuns(points, RidePersona.CYCLING).size)
    }

    @Test
    fun `edge inputs do not throw`() {
        assertTrue(RideGaps.recordedRuns(emptyList(), RidePersona.AUTO).isEmpty())
        assertEquals(1, RideGaps.recordedRuns(listOf(point(0, 1.0, 1.0)), RidePersona.AUTO).size)
        // Non-monotonic timestamps must not invent a speed.
        assertFalse(RideGaps.isUnrecordedGap(point(100, 12.97, 77.59), point(0, 12.99, 77.61), RidePersona.WALK))
    }
}
