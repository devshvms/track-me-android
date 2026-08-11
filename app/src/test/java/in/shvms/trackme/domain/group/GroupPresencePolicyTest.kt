package `in`.shvms.trackme.domain.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SCOPE_1.7.2 §3.6, §4.5 — amendments **A28** and **A29**.
 *
 * The composition cases are the reason this file exists. A strict precedence table would have
 * shipped a rider who is offline *with an undelivered "Need help"* being told only that updates are
 * paused — same root cause, but the consequence they must know is the second one.
 */
class GroupPresencePolicyTest {

    private val now = 900_000L
    private val interval = 10

    private val needHelp = RiderStatusCodec.parse(RiderStatusCatalog.NEED_HELP)!!
    private val engineHeat = RiderStatusCodec.parse(RiderStatusCatalog.ENGINE_HEAT)!!

    private fun input(
        sessionActive: Boolean = true,
        sessionStarted: Long = now,
        lastSync: Long? = now,
        lastPositionAck: Long? = now,
        failure: GroupPresencePolicy.FailureKind? = null,
        sharing: Boolean = true,
        riding: Boolean = true,
        status: RiderStatus? = null,
        acknowledged: Boolean = true,
    ) = GroupPresencePolicy.Input(
        sessionActive = sessionActive,
        sessionStartedElapsed = sessionStarted,
        lastSuccessfulSyncElapsed = lastSync,
        lastOwnPositionAckElapsed = lastPositionAck,
        lastFailureKind = failure,
        isSharingPosition = sharing,
        isRideRecording = riding,
        selfStatus = status,
        selfStatusAcknowledged = acknowledged,
        syncIntervalSec = interval,
        nowElapsed = now,
    )

    // --- Nothing to say -------------------------------------------------------------------------

    @Test
    fun `a healthy session with no status shows nothing`() {
        assertEquals(GroupPresencePolicy.Pill.None, GroupPresencePolicy.evaluate(input()))
    }

    @Test
    fun `an inactive session shows nothing, whatever else is true`() {
        val pill = GroupPresencePolicy.evaluate(
            input(sessionActive = false, sharing = false, status = needHelp, acknowledged = false),
        )
        assertEquals(GroupPresencePolicy.Pill.None, pill)
    }

    @Test
    fun `a session that has only just started is not yet paused`() {
        // It has not had a chance to fail. Showing "paused" before the first sync is due is a lie.
        assertEquals(
            GroupPresencePolicy.Pill.None,
            GroupPresencePolicy.evaluate(input(lastSync = null, sessionStarted = now - 5_000L)),
        )
    }

    @Test
    fun `a rider who joined while the relay was already down is still told`() {
        // The case a null-guard would swallow: there has never been a successful sync to measure
        // from, so staleness measures from the session start instead. Silently reassuring this rider
        // is the worst outcome available — they believe they are visible and they never were.
        val pill = GroupPresencePolicy.evaluate(
            input(
                lastSync = null,
                sessionStarted = now - 5 * 60_000L,
                failure = GroupPresencePolicy.FailureKind.SERVICE_UNAVAILABLE,
            ),
        )
        assertTrue(pill is GroupPresencePolicy.Pill.Paused)
        assertEquals(GroupPresencePolicy.Cause.RELAY, (pill as GroupPresencePolicy.Pill.Paused).cause)
    }

    // --- The threshold (§4.5) -------------------------------------------------------------------

    @Test
    fun `the threshold is the larger of thirty seconds and twice the advertised interval`() {
        assertEquals(30_000L, GroupPresencePolicy.pauseThresholdMillis(10))
        assertEquals(30_000L, GroupPresencePolicy.pauseThresholdMillis(15))
        // The relay slows everyone down under load; the threshold slows with it.
        assertEquals(60_000L, GroupPresencePolicy.pauseThresholdMillis(30))
        assertEquals(120_000L, GroupPresencePolicy.pauseThresholdMillis(60))
    }

    @Test
    fun `there is exactly one entry threshold, with no overlapping state below it`() {
        val threshold = GroupPresencePolicy.pauseThresholdMillis(interval)

        val justUnder = GroupPresencePolicy.evaluate(input(lastSync = now - threshold + 1))
        assertEquals(GroupPresencePolicy.Pill.None, justUnder)

        val exactly = GroupPresencePolicy.evaluate(
            input(lastSync = now - threshold, failure = GroupPresencePolicy.FailureKind.NO_INTERNET),
        )
        assertTrue(exactly is GroupPresencePolicy.Pill.Paused)
    }

    @Test
    fun `a successful sync is the only exit, and it clears the pill immediately`() {
        assertEquals(GroupPresencePolicy.Pill.None, GroupPresencePolicy.evaluate(input(lastSync = now)))
    }

    @Test
    fun `a slowed relay does not light up the pill on its own`() {
        // 45s since the last sync is a failure at a 10s cadence and perfectly normal at 30s.
        val stalled = input(lastSync = now - 45_000L, failure = GroupPresencePolicy.FailureKind.SERVICE_UNAVAILABLE)
        assertTrue(GroupPresencePolicy.evaluate(stalled) is GroupPresencePolicy.Pill.Paused)
        assertEquals(
            GroupPresencePolicy.Pill.None,
            GroupPresencePolicy.evaluate(stalled.copy(syncIntervalSec = 30)),
        )
    }

    // --- Cause is an explanation, never the failure itself ---------------------------------------

    @Test
    fun `no internet reads as the rider's own connection`() {
        val pill = GroupPresencePolicy.evaluate(
            input(lastSync = now - 60_000L, failure = GroupPresencePolicy.FailureKind.NO_INTERNET),
        ) as GroupPresencePolicy.Pill.Paused
        assertEquals(GroupPresencePolicy.Cause.LOCAL, pill.cause)
    }

    @Test
    fun `every other failure reads as ours, because blaming the rider for our outage is dishonest`() {
        listOf(
            GroupPresencePolicy.FailureKind.SERVICE_UNAVAILABLE,
            GroupPresencePolicy.FailureKind.AUTH,
            GroupPresencePolicy.FailureKind.PROTOCOL,
            null,
        ).forEach { kind ->
            val pill = GroupPresencePolicy.evaluate(
                input(lastSync = now - 60_000L, failure = kind),
            ) as GroupPresencePolicy.Pill.Paused
            assertEquals("$kind", GroupPresencePolicy.Cause.RELAY, pill.cause)
        }
    }

    @Test
    fun `connectivity alone never creates a pill while syncs are landing`() {
        // NET_CAPABILITY_VALIDATED flaps on captive portals; the pill must not strobe with it.
        assertEquals(
            GroupPresencePolicy.Pill.None,
            GroupPresencePolicy.evaluate(input(failure = GroupPresencePolicy.FailureKind.NO_INTERNET)),
        )
    }

    // --- The reassurance clause is only offered when it is true ----------------------------------

    @Test
    fun `the ride reassurance appears only while a ride is actually recording`() {
        val recording = GroupPresencePolicy.evaluate(
            input(lastSync = now - 60_000L, riding = true),
        ) as GroupPresencePolicy.Pill.Paused
        assertTrue(recording.rideRecording)

        val stopped = GroupPresencePolicy.evaluate(
            input(lastSync = now - 60_000L, riding = false),
        ) as GroupPresencePolicy.Pill.Paused
        assertFalse(stopped.rideRecording)
    }

    // --- Composition: the case a precedence table got wrong ---------------------------------------

    @Test
    fun `an undelivered alert is surfaced even while paused`() {
        val pill = GroupPresencePolicy.evaluate(
            input(
                lastSync = now - 60_000L,
                failure = GroupPresencePolicy.FailureKind.NO_INTERNET,
                status = needHelp,
                acknowledged = false,
            ),
        )
        assertTrue("paused must not swallow an undelivered alert", pill is GroupPresencePolicy.Pill.PausedWithUnsentAlert)
        pill as GroupPresencePolicy.Pill.PausedWithUnsentAlert
        assertEquals(GroupPresencePolicy.Cause.LOCAL, pill.cause)
        assertEquals(needHelp, pill.status)
    }

    @Test
    fun `an undelivered non-alert does not displace the paused clause`() {
        // Tiers 2 and 3 raise nobody; "engine heat didn't send" is not worth the pill's only line.
        val pill = GroupPresencePolicy.evaluate(
            input(lastSync = now - 60_000L, status = engineHeat, acknowledged = false),
        )
        assertTrue(pill is GroupPresencePolicy.Pill.Paused)
    }

    @Test
    fun `a delivered alert does not displace the paused clause either`() {
        val pill = GroupPresencePolicy.evaluate(
            input(lastSync = now - 60_000L, status = needHelp, acknowledged = true),
        )
        assertTrue(pill is GroupPresencePolicy.Pill.Paused)
    }

    // --- Not sharing outranks everything ----------------------------------------------------------

    @Test
    fun `not sharing wins over a paused group`() {
        val pill = GroupPresencePolicy.evaluate(
            input(sharing = false, lastSync = now - 60_000L, failure = GroupPresencePolicy.FailureKind.NO_INTERNET),
        )
        // No status, so no delivery claim about one.
        assertEquals(GroupPresencePolicy.Pill.NotSharing(null, false), pill)
    }

    @Test
    fun `a rider who is not sharing can still have said something`() {
        // §4.7 deliberately decouples status from position — this is the whole reason for the
        // separate slot, and the pill has to be able to express it.
        val pill = GroupPresencePolicy.evaluate(
            input(sharing = false, status = needHelp, acknowledged = true),
        ) as GroupPresencePolicy.Pill.NotSharing
        assertEquals(needHelp, pill.status)
        assertTrue(pill.statusAcknowledged)
    }

    // --- Healthy, with a status ---------------------------------------------------------------------

    @Test
    fun `a set and acknowledged status becomes the reminder chip`() {
        val pill = GroupPresencePolicy.evaluate(
            input(status = engineHeat, lastPositionAck = now - 12 * 60_000L),
        ) as GroupPresencePolicy.Pill.StatusReminder
        assertEquals(engineHeat, pill.status)
        assertEquals(PresenceAge.Bucket.Minutes(12), pill.age)
    }

    @Test
    fun `an unacknowledged status never claims delivery`() {
        val pill = GroupPresencePolicy.evaluate(input(status = engineHeat, acknowledged = false))
        assertEquals(GroupPresencePolicy.Pill.StatusUnsent(engineHeat), pill)
    }

    // --- "Last shared" reads the position ack, not the sync (§4.4) -----------------------------------

    @Test
    fun `a healthy sync with a stalled position ack still ages Last shared honestly`() {
        // The case that forced the two facts apart: relay reachable, TOO_FAST or frozen GPS, so the
        // group is seeing an old position while the network looks fine.
        val pill = GroupPresencePolicy.evaluate(
            input(
                lastSync = now,
                lastPositionAck = now - 4 * 60_000L,
                status = engineHeat,
            ),
        ) as GroupPresencePolicy.Pill.StatusReminder
        assertEquals(PresenceAge.Bucket.Minutes(4), pill.age)
    }

    @Test
    fun `a position that has never been accepted reads as unknown, not as zero`() {
        val pill = GroupPresencePolicy.evaluate(
            input(lastSync = now - 60_000L, lastPositionAck = null),
        ) as GroupPresencePolicy.Pill.Paused
        assertEquals(PresenceAge.Bucket.Unknown, pill.lastShared)
    }
}
