package `in`.shvms.trackme.domain.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SCOPE_1.8.7 §6.1.1 scenario 1 — the notification the app currently fails to send.
 *
 * The PRD marks "the user is told when a ride was auto-finalized" as a failing criterion. Today the
 * recovery happens silently, so someone whose phone died mid-ride opens the app expecting to have
 * lost it.
 */
class RecoveryNoticeTest {

    @Test
    fun `nothing recovered says nothing`() {
        assertNull(RecoveryNotice.decide(0, 0, "14:32", "12.3 km"))
    }

    @Test
    fun `a discarded empty ride is never announced`() {
        // A discarded ride had no GPS points: nothing was recorded, so nothing was lost. Announcing
        // it would be the app talking about its own housekeeping — and would read as "we deleted
        // something of yours", which is the opposite of what this notification exists to say.
        assertNull(RecoveryNotice.decide(recoveredCount = 0, discardedCount = 3, endedAtLabel = "14:32", distanceLabel = "1 km"))
    }

    @Test
    fun `one recovered ride carries its facts`() {
        assertEquals(
            RecoveryNotice.Notice.One(endedAtLabel = "14:32", distanceLabel = "12.3 km"),
            RecoveryNotice.decide(1, 0, "14:32", "12.3 km"),
        )
    }

    @Test
    fun `a missing fact drops both rather than half a sentence`() {
        // "Recording stopped at ." is worse than the plain version. A recovered ride with one point
        // and no measurable distance is a real case, not a hypothetical.
        assertEquals(
            RecoveryNotice.Notice.One(null, null),
            RecoveryNotice.decide(1, 0, endedAtLabel = "14:32", distanceLabel = null),
        )
        assertEquals(
            RecoveryNotice.Notice.One(null, null),
            RecoveryNotice.decide(1, 0, endedAtLabel = null, distanceLabel = "12.3 km"),
        )
        assertEquals(
            RecoveryNotice.Notice.One(null, null),
            RecoveryNotice.decide(1, 0, endedAtLabel = "  ", distanceLabel = "12.3 km"),
        )
    }

    @Test
    fun `several recovered rides report the count instead of one ride's time`() {
        // Naming a single end time when three rides were recovered would be actively misleading,
        // and listing all three is a notification nobody reads.
        assertEquals(
            RecoveryNotice.Notice.Many(3),
            RecoveryNotice.decide(3, 0, "14:32", "12.3 km"),
        )
    }

    @Test
    fun `recovery is never suppressed by the proactive budget`() {
        // Class A, not Class C. This one is about the user's data, and rationing it would mean a
        // ride recovered in a week when a recap already went out is a ride the user is never told
        // about. The budget object states the same thing; asserting it here is what stops a future
        // "unify all notifications through the budget" refactor from quietly reclassifying it.
        assertEquals(true, NotificationBudget.allows(
            NotificationBudget.Klass.CONSEQUENTIAL,
            nowMillis = 0,
            lastProactiveSentAtMillis = 0,
        ))
        assertEquals(false, NotificationBudget.Klass.CONSEQUENTIAL.spendsProactiveBudget)
    }
}
