package `in`.shvms.trackme.data.local

import `in`.shvms.trackme.domain.bulletin.BulletinEntry
import `in`.shvms.trackme.domain.bulletin.BulletinKind
import `in`.shvms.trackme.domain.notifications.BroadcastTag
import `in`.shvms.trackme.domain.notifications.OperatorBroadcast
import `in`.shvms.trackme.domain.recovery.OrphanedRideRecoveryManager
import `in`.shvms.trackme.domain.stats.WeeklyRecap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SCOPE_1.8.7 §6.1.7 — the adapters, and the one property that makes the bulletin trustworthy.
 *
 * "A copy of every notification actually sent" only works if the feed is **complete and
 * unduplicated**. A fact that interrupted someone and then is not in the feed is worse than one
 * that never interrupted — they saw it, swiped it, and now cannot find it. A fact that appears
 * twice makes the unread badge lie, and the badge is the only thing that surfaces the rows nothing
 * ever notified about.
 */
class BulletinAdaptersTest {

    @Test
    fun `a broadcast is keyed by its own id, so two arrivals are one row`() {
        // It genuinely arrives twice: by push, and by the foreground reconcile.
        val broadcast = OperatorBroadcast(
            id = "b1", tag = BroadcastTag.MAINTENANCE,
            title = "Cloud sync is paused", body = "Back in two hours.",
            createdAtMillis = 1_757_000_000_000,
            learnMoreUrl = "https://trackme.shvms.in/blogs",
        )
        val first = BulletinAdapters.from(broadcast)
        val second = BulletinAdapters.from(broadcast)
        assertEquals(first.id, second.id)
        assertEquals(BulletinKind.BROADCAST, first.kind)
        assertEquals("Cloud sync is paused", first.fact(BulletinEntry.FACT_TITLE))
        assertEquals("https://trackme.shvms.in/blogs", first.fact(BulletinEntry.FACT_LINK))
    }

    @Test
    fun `a broadcast with no link stores no link key`() {
        val entry = BulletinAdapters.from(
            OperatorBroadcast("b2", BroadcastTag.URGENT, "t", "b", 1)
        )
        assertNull(entry.fact(BulletinEntry.FACT_LINK))
    }

    @Test
    fun `a recap is keyed by week, so notified and read-in-app are one row`() {
        val recap = WeeklyRecap("2026-W30", 20_000, 3, 41_200.0, 6)
        assertEquals(BulletinAdapters.from(recap).id, BulletinAdapters.from(recap).id)
        assertEquals("3", BulletinAdapters.from(recap).fact(BulletinEntry.FACT_RIDE_COUNT))
    }

    @Test
    fun `a recap sorts by the week it describes, not when it was noticed`() {
        // Otherwise a recap read late sorts above facts that actually happened after it, and the
        // feed stops being a timeline of what happened.
        val older = BulletinAdapters.from(WeeklyRecap("2026-W29", 20_000, 2, 1.0, 1))
        val newer = BulletinAdapters.from(WeeklyRecap("2026-W30", 20_007, 2, 1.0, 2))
        assertTrue(newer.createdAtMillis > older.createdAtMillis)
    }

    @Test
    fun `recovery produces one row per ride, not one per recovery event`() {
        // The notification says "3 rides were saved" because it has one line. The feed has room to
        // say which three, and checking whether a particular ride survived is the reason to look.
        val summary = OrphanedRideRecoveryManager.RecoverySummary(
            recoveredCount = 3,
            discardedCount = 1,
            recovered = listOf(
                OrphanedRideRecoveryManager.RecoveredRide(1L, 1_000, 12_345.0),
                OrphanedRideRecoveryManager.RecoveredRide(2L, 2_000, 500.0),
                OrphanedRideRecoveryManager.RecoveredRide(3L, 3_000, 900.0),
            ),
        )
        val entries = BulletinAdapters.from(summary)
        assertEquals(3, entries.size)
        assertEquals(3, entries.map { it.id }.toSet().size)
        assertTrue(entries.all { it.kind == BulletinKind.RIDE_SAVED })
        assertEquals("12345.0", entries.first().fact(BulletinEntry.FACT_DISTANCE_METERS))
    }

    @Test
    fun `a discarded ride produces no row, matching the notification`() {
        // A discarded ride had no GPS points: nothing was recorded and nothing was lost. The
        // notification stays silent about it, and the feed must agree — a row saying an empty ride
        // was removed reads as "we deleted something of yours".
        val summary = OrphanedRideRecoveryManager.RecoverySummary(
            recoveredCount = 0, discardedCount = 4, recovered = emptyList()
        )
        assertTrue(BulletinAdapters.from(summary).isEmpty())
    }

    @Test
    fun `no adapter ever stores a coordinate, a title or an identifier that is not ours`() {
        // The bulletin renders on a screen someone may hand to a friend, and a ride title can be
        // anything a user typed. Facts are counts, distances, timestamps and ids.
        val entries = BulletinAdapters.from(
            OrphanedRideRecoveryManager.RecoverySummary(
                1, 0, listOf(OrphanedRideRecoveryManager.RecoveredRide(7L, 1_000, 10.0))
            )
        ) + BulletinAdapters.from(WeeklyRecap("2026-W30", 20_000, 3, 41_200.0, 6))

        val allowed = setOf(
            BulletinEntry.FACT_RIDE_COUNT,
            BulletinEntry.FACT_DISTANCE_METERS,
            BulletinEntry.FACT_STREAK_WEEKS,
            BulletinEntry.FACT_ENDED_AT_MILLIS,
        )
        entries.forEach { entry ->
            entry.facts.keys.forEach { key ->
                assertTrue("unexpected fact key '$key' — is it PII?", key in allowed)
            }
        }
    }
}
