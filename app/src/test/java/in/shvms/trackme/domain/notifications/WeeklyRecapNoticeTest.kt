package `in`.shvms.trackme.domain.notifications

import `in`.shvms.trackme.domain.stats.WeeklyRecap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SCOPE_1.8.7 §6.1.2 scenarios 8 and 10a — the flagship Class C.
 *
 * The recap already exists and is already deduped per week. What it is not, today, is reachable: it
 * appears only if you open the app on a calm Monday, which is exactly the population that needs it
 * least.
 */
class WeeklyRecapNoticeTest {

    private val week = 20_000L

    private fun recap(rides: Int = 3, weekStart: Long = week) = WeeklyRecap(
        weekKey = "2026-W30",
        weekStartEpochDay = weekStart,
        rideCount = rides,
        distanceMeters = 41_200.0,
        streakWeeks = 6,
    )

    @Test
    fun `a real week within budget is notified`() {
        assertTrue(
            WeeklyRecapNotice.shouldNotify(
                recap = recap(),
                nowMillis = NotificationBudget.PROACTIVE_INTERVAL_MILLIS,
                lastProactiveSentAtMillis = 0L,
                alreadyNotifiedWeekStart = null,
            )
        )
    }

    @Test
    fun `a zero-ride week is silent`() {
        // "You did nothing last week" is the exact message §4.2 N2 forbids — and it would arrive
        // automatically, every week, for anyone who had stopped riding. The selector already
        // guarantees this; restating it here means a change to the selector cannot quietly
        // reintroduce it.
        assertFalse(
            WeeklyRecapNotice.shouldNotify(
                recap = recap(rides = 0),
                nowMillis = NotificationBudget.PROACTIVE_INTERVAL_MILLIS,
                lastProactiveSentAtMillis = null,
                alreadyNotifiedWeekStart = null,
            )
        )
    }

    @Test
    fun `no recap means nothing to say`() {
        assertFalse(WeeklyRecapNotice.shouldNotify(null, 1_000L, null, null))
    }

    @Test
    fun `the budget suppresses a recap that is otherwise ready`() {
        assertFalse(
            WeeklyRecapNotice.shouldNotify(
                recap = recap(),
                nowMillis = NotificationBudget.PROACTIVE_INTERVAL_MILLIS - 1,
                lastProactiveSentAtMillis = 0L,
                alreadyNotifiedWeekStart = null,
            )
        )
    }

    @Test
    fun `a suppressed recap is still eligible once the week opens`() {
        // The property the cap rests on: skipping is free. A recap the budget refuses is not
        // consumed — it lands at the next calm moment inside its own week.
        val ready = recap()
        assertFalse(
            WeeklyRecapNotice.shouldNotify(ready, 1_000L, 0L, null)
        )
        assertTrue(
            WeeklyRecapNotice.shouldNotify(
                ready, NotificationBudget.PROACTIVE_INTERVAL_MILLIS, 0L, null
            )
        )
    }

    @Test
    fun `a week is never announced twice`() {
        assertFalse(
            WeeklyRecapNotice.shouldNotify(
                recap = recap(),
                nowMillis = NotificationBudget.PROACTIVE_INTERVAL_MILLIS * 4,
                lastProactiveSentAtMillis = 0L,
                alreadyNotifiedWeekStart = week,
            )
        )
        // ...but the next week is a different fact and may be.
        assertTrue(
            WeeklyRecapNotice.shouldNotify(
                recap = recap(weekStart = week + 7),
                nowMillis = NotificationBudget.PROACTIVE_INTERVAL_MILLIS * 4,
                lastProactiveSentAtMillis = 0L,
                alreadyNotifiedWeekStart = week,
            )
        )
    }

    @Test
    fun `a first recap is not owed a week of silence first`() {
        assertTrue(
            WeeklyRecapNotice.shouldNotify(recap(), 0L, null, null)
        )
    }

    // --- 10a: the level line that replaced a cut notification -----------------------------------

    @Test
    fun `proximity is mentioned when it is close and true`() {
        assertEquals(
            WeeklyRecapNotice.ProximityLine(20, "Explorer"),
            WeeklyRecapNotice.proximityLine(20, "Explorer"),
        )
    }

    @Test
    fun `nothing is said at the maximum level`() {
        assertNull(WeeklyRecapNotice.proximityLine(null, "Explorer"))
        assertNull(WeeklyRecapNotice.proximityLine(20, null))
        assertNull(WeeklyRecapNotice.proximityLine(20, "  "))
    }

    @Test
    fun `an already-reached level is not announced as zero minutes away`() {
        // The reveal already covered it. "0 minutes from Explorer" is the app failing to notice
        // something the user has already done.
        assertNull(WeeklyRecapNotice.proximityLine(0, "Explorer"))
        assertNull(WeeklyRecapNotice.proximityLine(-30, "Explorer"))
    }

    @Test
    fun `a distant level is left unmentioned`() {
        // True, and discouraging. The ceiling is what makes this a "you are nearly there" line
        // rather than a progress bar written out in prose.
        assertNull(
            WeeklyRecapNotice.proximityLine(
                WeeklyRecapNotice.MAX_MINUTES_WORTH_MENTIONING + 1, "Explorer"
            )
        )
        assertEquals(
            WeeklyRecapNotice.ProximityLine(WeeklyRecapNotice.MAX_MINUTES_WORTH_MENTIONING, "Explorer"),
            WeeklyRecapNotice.proximityLine(WeeklyRecapNotice.MAX_MINUTES_WORTH_MENTIONING, "Explorer"),
        )
    }

    @Test
    fun `the recap spends the budget and an operator broadcast does not`() {
        // The two Class C and Class D rules meeting in one place. A recap consumes the week; a
        // broadcast sent the same day must neither be blocked by it nor consume it.
        val sent = NotificationBudget.recordSent(NotificationBudget.Klass.PROACTIVE, 5_000L, null)
        assertEquals(5_000L, sent)
        assertEquals(sent, NotificationBudget.recordSent(NotificationBudget.Klass.OPERATOR, 6_000L, sent))
        assertTrue(NotificationBudget.allows(NotificationBudget.Klass.OPERATOR, 5_001L, sent))
        assertFalse(NotificationBudget.allows(NotificationBudget.Klass.PROACTIVE, 5_001L, sent))
    }
}
