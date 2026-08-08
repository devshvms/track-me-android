package `in`.shvms.trackme.domain.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SCOPE_1.7.0 §2.9 — the measurement that justifies shipping the estimator dark.
 *
 * The estimator itself is covered by `EtaEstimateTest`; this covers the thing that makes it worth
 * having in 1.7.x at all. If the sample never fires, or fires with the wrong numbers, 1.8 arrives
 * with no error distribution and the release was wasted rather than deferred.
 */
class DestinationProgressTest {

    // ~1.1 km apart along a meridian.
    private val destLat = 12.9816
    private val destLng = 77.5946
    private val startLat = 12.9716
    private val startLng = 77.5946

    private val t0 = 1_785_000_000_000L

    private fun progress(persona: String? = "BIKE") =
        DestinationProgress(destLat, destLng, persona)

    // --- Distance ---------------------------------------------------------------------------

    @Test
    fun `haversine gives a sane distance`() {
        val d = DestinationProgress.haversineMeters(startLat, startLng, destLat, destLng)
        assertTrue("expected ~1.1km, got $d", d in 1_000.0..1_300.0)
        assertEquals(0.0, DestinationProgress.haversineMeters(destLat, destLng, destLat, destLng), 0.001)
    }

    // --- The calibration sample ------------------------------------------------------------------

    @Test
    fun `a sample is emitted on arrival, with the first prediction and the real elapsed time`() {
        val p = progress()
        // Far away, moving at 5 m/s. ~1.1km / 5 = ~220s predicted.
        assertNull(p.onPosition(startLat, startLng, 5.0, t0))
        val predicted = (p.currentEstimate as EtaEstimate.Eta).secondsRemaining
        assertTrue("no usable prediction was made", predicted > 100)

        // Arrive four minutes later.
        val sample = p.onPosition(destLat, destLng, 5.0, t0 + 240_000L)
        assertNotNull("no calibration sample on arrival", sample)
        assertEquals(predicted, sample!!.predictedSeconds)
        assertEquals(240L, sample.actualSeconds)
        assertEquals("BIKE", sample.persona)
    }

    @Test
    fun `the sample fires exactly once, however long the member loiters`() {
        // A member standing at the meeting point would otherwise emit the same measurement every
        // ten seconds and swamp the distribution 1.8 depends on.
        val p = progress()
        p.onPosition(startLat, startLng, 5.0, t0)
        assertNotNull(p.onPosition(destLat, destLng, 5.0, t0 + 240_000L))
        for (i in 1..20) {
            assertNull("re-emitted after arrival", p.onPosition(destLat, destLng, 0.0, t0 + 240_000L + i * 10_000L))
        }
    }

    @Test
    fun `arriving with no usable prediction emits nothing rather than a meaningless sample`() {
        // Someone who was stationary the whole way, or who joined already at the destination, has
        // no prediction to score. A sample built from a Stopped estimate would measure the wrong
        // thing and pollute the data.
        val p = progress()
        p.onPosition(startLat, startLng, 0.0, t0) // stationary — Stopped, not a prediction
        assertNull(p.onPosition(destLat, destLng, 0.0, t0 + 60_000L))
    }

    @Test
    fun `arrival is not declared until inside the persona's radius`() {
        val p = progress("WALK")
        assertNull(p.onPosition(startLat, startLng, 1.5, t0))
        assertFalse(p.hasArrived())
        // ~50m out: outside WALK's 40m radius.
        assertNull(p.onPosition(destLat - 0.00045, destLng, 1.5, t0 + 60_000L))
        assertFalse(p.hasArrived())
        assertNotNull(p.onPosition(destLat, destLng, 1.5, t0 + 120_000L))
        assertTrue(p.hasArrived())
    }

    // --- The estimate itself ---------------------------------------------------------------------

    @Test
    fun `speed is smoothed, so one bad fix cannot swing the prediction`() {
        val p = progress()
        // Actually approaching, a little each step — a member holding position would be
        // "not closing", which is a different case (below).
        repeat(4) { p.onPosition(startLat + it * 0.0005, startLng, 5.0, t0 + it * 10_000L) }
        val steady = (p.currentEstimate as EtaEstimate.Eta).secondsRemaining

        // One absurd fix.
        p.onPosition(startLat + 4 * 0.0005, startLng, 40.0, t0 + 50_000L)
        val afterSpike = (p.currentEstimate as EtaEstimate.Eta).secondsRemaining

        // A median ignores the outlier entirely; a mean halved the estimate, which is how a
        // 144 km/h glitch under a bridge would have become a confidently wrong ETA in 1.8.
        assertTrue(
            "a single GPS spike moved the estimate: ${'$'}steady -> ${'$'}afterSpike",
            afterSpike > steady * 0.8,
        )
    }

    @Test
    fun `GPS jitter at a standstill does not read as moving away`() {
        // A strict "distance must shrink" would flip to no-estimate on ordinary jitter, punching
        // holes in the calibration data for a member who is simply riding steadily.
        val p = progress()
        p.onPosition(startLat, startLng, 5.0, t0)
        // A few metres the wrong way — well inside consumer GPS noise.
        p.onPosition(startLat - 0.00005, startLng, 5.0, t0 + 10_000L)
        assertTrue(
            "jitter was treated as a detour: ${'$'}{p.currentEstimate}",
            p.currentEstimate is EtaEstimate.Eta,
        )
    }

    @Test
    fun `moving away yields no estimate rather than a growing one`() {
        // §8: "Estimator returns 'no estimate' rather than a growing one. Never editorialise."
        val p = progress()
        p.onPosition(startLat, startLng, 5.0, t0)
        p.onPosition(startLat - 0.01, startLng, 5.0, t0 + 10_000L) // ~1.1km further — a real detour
        assertEquals(EtaEstimate.None, p.currentEstimate)
    }

    @Test
    fun `a member who stops reads as Stopped rather than dividing by zero`() {
        val p = progress()
        repeat(6) { p.onPosition(startLat, startLng, 0.0, t0 + it * 10_000L) }
        assertEquals(EtaEstimate.Stopped, p.currentEstimate)
    }

    @Test
    fun `a missing speed does not crash or fabricate movement`() {
        val p = progress()
        assertNull(p.onPosition(startLat, startLng, null, t0))
        assertEquals(EtaEstimate.Stopped, p.currentEstimate)
    }

    @Test
    fun `the estimate is computed even though it is never displayed`() {
        // §2.9's whole design: the flag gates the DISPLAY, not the measurement. If this ever
        // becomes conditional on SHOW_ETA, the calibration data stops being collected and 1.8 has
        // nothing to turn on.
        assertFalse(GroupFeatureFlags.SHOW_ETA)
        val p = progress()
        p.onPosition(startLat, startLng, 5.0, t0)
        assertTrue(p.currentEstimate is EtaEstimate.Eta)
    }
}
