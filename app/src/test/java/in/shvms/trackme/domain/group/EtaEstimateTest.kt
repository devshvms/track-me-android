package `in`.shvms.trackme.domain.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SCOPE_1.7.0 §2.9 — the machinery that ships **dark**.
 *
 * §2.9 is blunt that building this without a UI is how dead code gets made, and that the defence
 * is a pure policy type *"testable to completion without a UI, which is what stops them rotting."*
 * This is that test suite, and it is the only thing exercising this code until 1.8 turns the
 * display on. It therefore has to cover the behaviour, not just the happy path.
 */
class EtaEstimateTest {

    // --- The estimate ---------------------------------------------------------------------------

    @Test
    fun `a steady approach produces a proportional estimate`() {
        val eta = EtaEstimate.from(distanceMeters = 1_000.0, rollingSpeedMps = 5.0)
        assertEquals(EtaEstimate.Eta(200L, 1_000.0), eta)
    }

    @Test
    fun `halving the speed doubles the estimate`() {
        val fast = EtaEstimate.from(1_000.0, 10.0) as EtaEstimate.Eta
        val slow = EtaEstimate.from(1_000.0, 5.0) as EtaEstimate.Eta
        assertEquals(fast.secondsRemaining * 2, slow.secondsRemaining)
    }

    @Test
    fun `a stationary member clamps to Stopped rather than dividing by zero`() {
        // §8: "Member is stationary → ETA divides by ~zero. Clamp to a Stopped state in the
        // estimator. The estimator must still handle it correctly because the calibration event
        // depends on it."
        assertEquals(EtaEstimate.Stopped, EtaEstimate.from(1_000.0, 0.0))
        assertEquals(EtaEstimate.Stopped, EtaEstimate.from(1_000.0, 0.1))
        assertEquals(EtaEstimate.Stopped, EtaEstimate.from(1_000.0, EtaEstimate.MIN_SPEED_MPS - 0.01))
    }

    @Test
    fun `no estimate is produced when moving away, rather than a growing one`() {
        // §8: "Estimator returns 'no estimate' rather than a growing one. Never editorialise
        // ('wrong way') — a detour is not an error."
        assertEquals(EtaEstimate.None, EtaEstimate.from(1_000.0, 8.0, closing = false))
    }

    @Test
    fun `arriving reads as zero rather than a tiny estimate`() {
        val eta = EtaEstimate.from(30.0, 4.0) as EtaEstimate.Eta
        assertEquals(0L, eta.secondsRemaining)
    }

    @Test
    fun `an arriving member is not reported as Stopped just because they slowed down`() {
        // Someone coasting to a stop at the meeting point is arriving, not stalled. Getting this
        // backwards would make every successful arrival look like a failure in the calibration data.
        assertTrue(EtaEstimate.from(20.0, 0.0) is EtaEstimate.Eta)
    }

    @Test
    fun `nonsense inputs produce no estimate instead of a number`() {
        // This runs against live GPS for hours; NaN and negative distance are not hypothetical.
        assertEquals(EtaEstimate.None, EtaEstimate.from(Double.NaN, 5.0))
        assertEquals(EtaEstimate.None, EtaEstimate.from(1_000.0, Double.NaN))
        assertEquals(EtaEstimate.None, EtaEstimate.from(-5.0, 5.0))
        assertEquals(EtaEstimate.None, EtaEstimate.from(Double.POSITIVE_INFINITY, 5.0))
    }

    @Test
    fun `an implausibly high speed still yields a finite estimate`() {
        val eta = EtaEstimate.from(1_000.0, 1_000.0) as EtaEstimate.Eta
        assertTrue(eta.secondsRemaining >= 0)
    }

    // --- Arrival -------------------------------------------------------------------------------

    @Test
    fun `arrival radius varies by persona`() {
        // 60m is generous on foot and tight in a car park.
        assertTrue(ArrivalPolicy.radiusMetersFor("WALK") < ArrivalPolicy.radiusMetersFor("BIKE"))
        assertTrue(ArrivalPolicy.radiusMetersFor("BIKE") < ArrivalPolicy.radiusMetersFor("DRIVE"))
    }

    @Test
    fun `an unknown or missing persona still has a radius`() {
        assertTrue(ArrivalPolicy.radiusMetersFor(null) > 0)
        assertTrue(ArrivalPolicy.radiusMetersFor("SOMETHING_NEW") > 0)
    }

    @Test
    fun `persona matching is case-insensitive`() {
        assertEquals(ArrivalPolicy.radiusMetersFor("WALK"), ArrivalPolicy.radiusMetersFor("walk"), 0.001)
    }

    @Test
    fun `arrival triggers inside the radius and not outside it`() {
        assertTrue(ArrivalPolicy.hasArrived(10.0, "WALK"))
        assertTrue(ArrivalPolicy.hasArrived(40.0, "WALK"))
        assertFalse(ArrivalPolicy.hasArrived(41.0, "WALK"))
        assertFalse(ArrivalPolicy.hasArrived(1_000.0, "BIKE"))
    }

    @Test
    fun `a nonsense distance never counts as arrival`() {
        assertFalse(ArrivalPolicy.hasArrived(Double.NaN, "WALK"))
        assertFalse(ArrivalPolicy.hasArrived(-1.0, "WALK"))
    }

    // --- Calibration: the reason to build this a release early -----------------------------------

    @Test
    fun `a sample carries the error in both absolute and percentage terms`() {
        // §2.9: "predicted-vs-actual duration, and the absolute and percentage error."
        val sample = EtaCalibration.sampleFor(predictedSeconds = 600, actualSeconds = 900, persona = "BIKE")!!
        assertEquals(600L, sample.predictedSeconds)
        assertEquals(900L, sample.actualSeconds)
        assertEquals(300L, sample.absoluteErrorSeconds)
        assertEquals(33, sample.percentageError)
        assertEquals("BIKE", sample.persona)
    }

    @Test
    fun `error is absolute, so an over-estimate is not cancelled by an under-estimate`() {
        val over = EtaCalibration.sampleFor(900, 600, "BIKE")!!
        val under = EtaCalibration.sampleFor(600, 900, "BIKE")!!
        assertTrue(over.absoluteErrorSeconds > 0)
        assertTrue(under.absoluteErrorSeconds > 0)
        assertEquals(over.absoluteErrorSeconds, under.absoluteErrorSeconds)
    }

    @Test
    fun `a perfect prediction reports zero error`() {
        val sample = EtaCalibration.sampleFor(600, 600, "WALK")!!
        assertEquals(0L, sample.absoluteErrorSeconds)
        assertEquals(0, sample.percentageError)
    }

    @Test
    fun `nothing is emitted when there is nothing to learn`() {
        assertNull(EtaCalibration.sampleFor(0, 900, "BIKE"))
        assertNull(EtaCalibration.sampleFor(600, 0, "BIKE"))
        assertNull(EtaCalibration.sampleFor(-1, 900, "BIKE"))
    }

    @Test
    fun `a sample carries no coordinate, destination, or group identity`() {
        // §2.9 and §9: "No coordinates, no destination, no group identity — just two durations and
        // a persona." A test rather than a comment, because this is the one thing that outlives a
        // group and the temptation to enrich it later will be real.
        val fields = EtaCalibration.Sample::class.java.declaredFields.map { it.name.lowercase() }
        for (forbidden in listOf("lat", "lng", "longitude", "latitude", "groupid", "uid", "destination", "name")) {
            assertFalse(
                "calibration sample grew a \"$forbidden\" field — see §2.9",
                fields.any { it.contains(forbidden) },
            )
        }
    }

    // --- The flag (§2.9 mitigation 2) --------------------------------------------------------------

    @Test
    fun `the display stays off in 1_7_x`() {
        // §2.9: "GROUP_SHOW_ETA defaults off… so 1.8 can enable the display for a cohort without a
        // client release." If this ever flips without the calibration data, the release ships the
        // confidently-wrong ETA D7 was revised to avoid.
        assertFalse("ETA display was enabled before 1.8", GroupFeatureFlags.SHOW_ETA)
        assertFalse("arrival display was enabled before 1.8", GroupFeatureFlags.SHOW_ARRIVAL)
    }
}
