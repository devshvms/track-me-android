package `in`.shvms.trackme.domain.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SCOPE_1.8.7 §6.1.5 scenario 23.
 *
 * "A user who enabled sync believes their data is safe. Silent persistent failure is the worst
 * class of bug in a data-ownership product." The two ways to get this wrong are opposite and both
 * bad: say nothing and the user finds out when the phone is lost; say it on every failure and the
 * channel is noise before the real problem arrives.
 */
class SyncFailureNoticeTest {

    private val threshold = SyncFailureNotice.CONSECUTIVE_FAILURES_BEFORE_NOTICE

    @Test
    fun `one failure says nothing`() {
        // Sync fails constantly and harmlessly: a tunnel, a café network, airplane mode. Notifying
        // on the first would have the app crying wolf within a day.
        assertFalse(SyncFailureNotice.shouldNotify(1, unsyncedRideCount = 5, alreadyNotifiedThisEpisode = false))
        assertFalse(SyncFailureNotice.shouldNotify(threshold - 1, 5, false))
    }

    @Test
    fun `persistent failure with rides waiting is reported`() {
        assertTrue(SyncFailureNotice.shouldNotify(threshold, 5, false))
        assertTrue(SyncFailureNotice.shouldNotify(threshold + 20, 1, false))
    }

    @Test
    fun `nothing waiting means nothing at risk`() {
        // A failing sync with an empty queue is the app reporting its own internal state, which is
        // not a fact about the user (§4.2 N1).
        assertFalse(SyncFailureNotice.shouldNotify(threshold + 10, unsyncedRideCount = 0, alreadyNotifiedThisEpisode = false))
    }

    @Test
    fun `an episode is reported once, not once per failure`() {
        // A device offline for a week produces dozens of failures and exactly one problem.
        assertFalse(SyncFailureNotice.shouldNotify(threshold + 30, 5, alreadyNotifiedThisEpisode = true))
    }

    @Test
    fun `a success ends the episode, so a second breakage is reported again`() {
        // "Once per episode" has to mean an episode, not "once ever". A user whose backup breaks
        // twice in a year should be told twice.
        var state = SyncFailureNotice.Episode()
        repeat(threshold) { state = SyncFailureNotice.record(succeeded = false, state = state) }
        assertEquals(threshold, state.consecutiveFailures)
        assertTrue(SyncFailureNotice.shouldNotify(state.consecutiveFailures, 5, state.notified))

        state = state.copy(notified = true)
        assertFalse(SyncFailureNotice.shouldNotify(state.consecutiveFailures, 5, state.notified))

        state = SyncFailureNotice.record(succeeded = true, state = state)
        assertEquals(SyncFailureNotice.Episode(), state)
        assertFalse("a fresh episode has nothing to report yet", SyncFailureNotice.shouldNotify(state.consecutiveFailures, 5, state.notified))

        repeat(threshold) { state = SyncFailureNotice.record(succeeded = false, state = state) }
        assertTrue("the second breakage is a new episode", SyncFailureNotice.shouldNotify(state.consecutiveFailures, 5, state.notified))
    }

    @Test
    fun `an intermittent connection never accumulates to a false alarm`() {
        // The realistic pattern: fail, fail, succeed, fail, fail, succeed. Two short outages, no
        // problem, and the counter must not carry across the successes.
        var state = SyncFailureNotice.Episode()
        listOf(false, false, true, false, false, true, false).forEach { ok ->
            state = SyncFailureNotice.record(succeeded = ok, state = state)
            assertFalse(
                "an intermittent connection must never trip the notice",
                SyncFailureNotice.shouldNotify(state.consecutiveFailures, 5, state.notified),
            )
        }
    }

    @Test
    fun `the first episode after an upgrade is not consumed in silence`() {
        // Codex review finding 3. The last-success key does not exist until Track 2 has seen a sync
        // succeed, so the FIRST failing episode after an install or upgrade has no date to quote.
        // The old code marked the episode reported and then bailed out because it had no date,
        // producing an invisible bulletin row and no notification — for exactly the population that
        // has never had a working backup, and with no second chance until a success reset the flag.
        //
        // The decision itself never depended on the date, and this pins that: a missing timestamp
        // must not change whether the user is told.
        assertTrue(
            "a failing backup with no prior success is still a failing backup",
            SyncFailureNotice.shouldNotify(threshold, unsyncedRideCount = 5, alreadyNotifiedThisEpisode = false),
        )
    }

    @Test
    fun `a broken backup is never suppressed by the proactive budget`() {
        // Class A. A backup that broke during a week when a recap went out is still broken, and
        // asserting the classification here stops a later "unify through the budget" refactor.
        assertTrue(NotificationBudget.allows(NotificationBudget.Klass.CONSEQUENTIAL, 0, 0))
        assertFalse(NotificationBudget.Klass.CONSEQUENTIAL.spendsProactiveBudget)
    }
}
