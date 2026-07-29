package `in`.shvms.trackme.domain.stats

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-119 regression coverage for [CalmMomentGate] — the pure "is now a calm moment to show a
 * non-urgent celebration" decision behind the B2 weekly recap.
 *
 * Prompt 09 ("Trigger") forbids the recap during an active/paused ride, an SOS flow, or a
 * GPS-lost/storage-low state. Before this task the recap only checked for a pending B1 reveal, so
 * every other non-idle state let it through.
 */
class CalmMomentGateTest {

    private fun moment(
        isTrackingIdle: Boolean = true,
        isEmergencyActive: Boolean = false,
        hasPendingReveal: Boolean = false
    ) = CalmMomentGate.AppMoment(
        isTrackingIdle = isTrackingIdle,
        isEmergencyActive = isEmergencyActive,
        hasPendingReveal = hasPendingReveal
    )

    @Test
    fun `idle with nothing pending is calm`() {
        assertTrue(CalmMomentGate.isCalm(moment()))
    }

    @Test
    fun `defaults describe the calm happy path`() {
        assertTrue(CalmMomentGate.isCalm(CalmMomentGate.AppMoment()))
    }

    @Test
    fun `a non-idle tracking state is never calm`() {
        // Covers TRACKING, PAUSED, GPS_LOST, GPS_DISABLED and STORAGE_LOW: the gate takes the
        // already-mapped boolean, so every non-IDLE state collapses to this single case.
        assertFalse(CalmMomentGate.isCalm(moment(isTrackingIdle = false)))
    }

    @Test
    fun `an active emergency is never calm even when tracking is idle`() {
        // The SOS can outlive the ride (user stops tracking, SOS still in flight). Safety-critical:
        // a celebration must never cover the emergency surface.
        assertFalse(CalmMomentGate.isCalm(moment(isEmergencyActive = true)))
    }

    @Test
    fun `a pending post-ride reveal is never calm`() {
        assertFalse(CalmMomentGate.isCalm(moment(hasPendingReveal = true)))
    }

    @Test
    fun `every condition is independently blocking`() {
        assertFalse(
            CalmMomentGate.isCalm(
                moment(isTrackingIdle = false, isEmergencyActive = true, hasPendingReveal = true)
            )
        )
        assertFalse(CalmMomentGate.isCalm(moment(isTrackingIdle = false, isEmergencyActive = true)))
        assertFalse(CalmMomentGate.isCalm(moment(isEmergencyActive = true, hasPendingReveal = true)))
        assertFalse(CalmMomentGate.isCalm(moment(isTrackingIdle = false, hasPendingReveal = true)))
    }
}
