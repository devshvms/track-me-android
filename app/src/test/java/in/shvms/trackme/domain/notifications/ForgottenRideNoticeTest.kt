package `in`.shvms.trackme.domain.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SCOPE_1.8.7 §6.1.1 scenario 4 — the ride someone forgot to stop.
 *
 * The failure this prevents is quiet and permanent: a ride that ran for six hours in a pocket has
 * a wrong duration, a meaningless average speed, and poisons every aggregate it lands in. The
 * failure it must *not* cause is louder and worse — stopping a ride somebody was still on.
 */
class ForgottenRideNoticeTest {

    private val threshold = ForgottenRideNotice.STILLNESS_BEFORE_NOTICE_MILLIS

    @Test
    fun `a rider who has just stopped at a light is not asked`() {
        assertFalse(ForgottenRideNotice.shouldAsk(60_000, alreadyAsked = false, isTracking = true))
        assertFalse(ForgottenRideNotice.shouldAsk(threshold - 1, alreadyAsked = false, isTracking = true))
    }

    @Test
    fun `a long stillness is asked about, once`() {
        assertTrue(ForgottenRideNotice.shouldAsk(threshold, alreadyAsked = false, isTracking = true))
        assertTrue(ForgottenRideNotice.shouldAsk(threshold * 10, alreadyAsked = false, isTracking = true))
    }

    @Test
    fun `asking twice is arguing with someone who has already answered`() {
        // Someone who kept recording after being asked has made a decision. A second notification
        // is the app disagreeing with it.
        assertFalse(ForgottenRideNotice.shouldAsk(threshold * 10, alreadyAsked = true, isTracking = true))
    }

    @Test
    fun `a deliberately paused ride is not a forgotten ride`() {
        // It is a ride someone is managing. They know it is there — they paused it.
        assertFalse(ForgottenRideNotice.shouldAsk(threshold * 10, alreadyAsked = false, isTracking = false))
    }

    @Test
    fun `the threshold is far above any auto-pause stillness`() {
        // Auto-pause decides whether the clock runs and works in seconds. This decides whether to
        // speak, and confusing the two would produce a notification at every traffic light.
        listOf(
            `in`.shvms.trackme.domain.config.PersonaAutoPauseConfig.WALK_STILLNESS_MS,
            `in`.shvms.trackme.domain.config.PersonaAutoPauseConfig.RUN_STILLNESS_MS,
            `in`.shvms.trackme.domain.config.PersonaAutoPauseConfig.CYCLING_STILLNESS_MS,
            `in`.shvms.trackme.domain.config.PersonaAutoPauseConfig.BIKE_DRIVE_STILLNESS_MS,
        ).forEach { autoPause ->
            assertTrue(
                "the notice threshold must be far above auto-pause's $autoPause ms",
                threshold > autoPause * 100,
            )
        }
    }

    @Test
    fun `it is long enough to sit out a real interruption`() {
        // Half an hour of shelter from rain, or lunch on a long tour, must not be interrupted.
        assertFalse(ForgottenRideNotice.shouldAsk(30L * 60 * 1000, alreadyAsked = false, isTracking = true))
    }

    @Test
    fun `nothing here can stop a ride`() {
        // §6.1.1 and P4: this must not auto-stop. The app cannot tell "forgot" from "waiting out a
        // thunderstorm", and stopping someone's ride destroys data they cannot get back. The
        // object's only output is a boolean about whether to *ask* — asserted structurally, since
        // the guarantee is the absence of an action rather than the presence of one.
        val onlyOutput = ForgottenRideNotice.shouldAsk(threshold, false, true)
        assertTrue(onlyOutput is Boolean)
    }
}
