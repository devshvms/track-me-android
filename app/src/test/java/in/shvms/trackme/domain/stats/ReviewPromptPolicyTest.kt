package `in`.shvms.trackme.domain.stats

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the pure B4 [ReviewPromptPolicy] gating. */
class ReviewPromptPolicyTest {

    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun eligible_whenEnoughRides_neverPrompted_newVersion() {
        assertTrue(
            ReviewPromptPolicy.isEligible(
                goodRideCount = 3, lastPromptedAtMillis = 0L, lastPromptedVersion = null,
                currentVersion = "1.6.0", nowMillis = now
            )
        )
    }

    @Test
    fun notEligible_belowRideThreshold() {
        assertFalse(
            ReviewPromptPolicy.isEligible(2, 0L, null, "1.6.0", now)
        )
    }

    @Test
    fun notEligible_sameVersionAlreadyAsked() {
        assertFalse(
            ReviewPromptPolicy.isEligible(10, now - 400L * day, "1.6.0", "1.6.0", now)
        )
    }

    @Test
    fun notEligible_withinCooldown_evenOnNewVersion() {
        assertFalse(
            ReviewPromptPolicy.isEligible(10, now - 30L * day, "1.5.0", "1.6.0", now)
        )
    }

    @Test
    fun eligible_afterCooldown_onNewVersion() {
        assertTrue(
            ReviewPromptPolicy.isEligible(10, now - 91L * day, "1.5.0", "1.6.0", now)
        )
    }

    @Test
    fun cooldownBoundary_exactly90Days_isEligible() {
        assertTrue(
            ReviewPromptPolicy.isEligible(5, now - 90L * day, "1.5.0", "1.6.0", now)
        )
    }
}
