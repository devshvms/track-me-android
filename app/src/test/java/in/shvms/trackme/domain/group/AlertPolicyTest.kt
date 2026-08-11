package `in`.shvms.trackme.domain.group

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SCOPE_1.7.2 §5.2 — the alert-fatigue rules, case by case.
 *
 * These are the release's highest-risk half: an alert that fires wrongly twice gets muted forever,
 * and then it is not there on the day it matters. Each rule is a test rather than a hope.
 */
class AlertPolicyTest {

    private val needHelp = RiderStatusCodec.parse(RiderStatusCatalog.NEED_HELP)!!
    private val crashed = RiderStatusCodec.parse(RiderStatusCatalog.CRASHED)!!
    private val tired = RiderStatusCodec.parse(RiderStatusCatalog.TIRED)!!
    private val engineHeat = RiderStatusCodec.parse(RiderStatusCatalog.ENGINE_HEAT)!!

    private fun input(
        member: String = "uid-ravi",
        self: String? = "uid-me",
        previous: RiderStatus? = null,
        current: RiderStatus? = null,
        raisedForPrevious: Boolean = false,
        senderStale: Boolean = false,
        muted: Boolean = false,
        sinceJoin: Long = 10 * 60_000L,
    ) = AlertPolicy.Input(
        memberUid = member,
        selfUid = self,
        previous = previous,
        current = current,
        raisedForPrevious = raisedForPrevious,
        senderStale = senderStale,
        muted = muted,
        millisSinceJoin = sinceJoin,
    )

    @Test
    fun `entering severity one raises the alert`() {
        assertEquals(
            AlertPolicy.Signal.ALERT_RAISED,
            AlertPolicy.signalFor(input(current = needHelp)),
        )
    }

    @Test
    fun `the same status arriving again on every sync does not re-alert`() {
        // Sync is a STATE snapshot, not an event stream. This is the bug that gets written when the
        // rule is not written down.
        assertEquals(
            AlertPolicy.Signal.NONE,
            AlertPolicy.signalFor(input(previous = needHelp, current = needHelp, raisedForPrevious = true)),
        )
    }

    @Test
    fun `swapping between two alert statuses does not re-alert`() {
        // Still severity 1 throughout — the group is already looking.
        assertEquals(
            AlertPolicy.Signal.NONE,
            AlertPolicy.signalFor(input(previous = needHelp, current = crashed, raisedForPrevious = true)),
        )
    }

    @Test
    fun `tiers two and three never interrupt anyone`() {
        listOf(tired, engineHeat).forEach { status ->
            assertEquals(
                "$status must not alert",
                AlertPolicy.Signal.NONE,
                AlertPolicy.signalFor(input(current = status)),
            )
        }
    }

    @Test
    fun `your own status never alerts you`() {
        assertEquals(
            AlertPolicy.Signal.NONE,
            AlertPolicy.signalFor(input(member = "uid-me", current = needHelp)),
        )
    }

    @Test
    fun `an alert attached to a stale position is history, not news`() {
        assertEquals(
            AlertPolicy.Signal.NONE,
            AlertPolicy.signalFor(input(current = needHelp, senderStale = true)),
        )
    }

    @Test
    fun `a latecomer is not ambushed by standing statuses`() {
        assertEquals(
            AlertPolicy.Signal.NONE,
            AlertPolicy.signalFor(input(current = needHelp, sinceJoin = 5_000L)),
        )
        // ...but the grace window does expire.
        assertEquals(
            AlertPolicy.Signal.ALERT_RAISED,
            AlertPolicy.signalFor(input(current = needHelp, sinceJoin = AlertPolicy.JOIN_GRACE_MS)),
        )
    }

    @Test
    fun `muting silences interruption`() {
        assertEquals(
            AlertPolicy.Signal.NONE,
            AlertPolicy.signalFor(input(current = needHelp, muted = true)),
        )
    }

    // --- Resolutions (§3.7) --------------------------------------------------------------------

    @Test
    fun `clearing an alert tells the people who were alerted`() {
        // An alarm with no resolution leaves the group riding back to a problem that ended.
        assertEquals(
            AlertPolicy.Signal.ALERT_RESOLVED,
            AlertPolicy.signalFor(input(previous = needHelp, current = null, raisedForPrevious = true)),
        )
    }

    @Test
    fun `dropping to a lower tier is also a resolution`() {
        assertEquals(
            AlertPolicy.Signal.ALERT_RESOLVED,
            AlertPolicy.signalFor(input(previous = needHelp, current = tired, raisedForPrevious = true)),
        )
    }

    @Test
    fun `a resolution is not sent to someone who never got the alarm`() {
        // A "cleared" notification for something you never knew about is pure noise. This is the
        // latecomer, and the rider who had alerts muted at the time.
        assertEquals(
            AlertPolicy.Signal.NONE,
            AlertPolicy.signalFor(input(previous = needHelp, current = null, raisedForPrevious = false)),
        )
    }

    @Test
    fun `a stale sender does not block a resolution`() {
        // Staleness suppresses the alarm because an old alert is not news. A resolution is the
        // opposite: it is the newest thing we know, and the people who reacted need it.
        assertEquals(
            AlertPolicy.Signal.ALERT_RESOLVED,
            AlertPolicy.signalFor(
                input(previous = needHelp, current = null, raisedForPrevious = true, senderStale = true),
            ),
        )
    }

    @Test
    fun `muting also silences resolutions, since it silenced the alarm`() {
        assertEquals(
            AlertPolicy.Signal.NONE,
            AlertPolicy.signalFor(
                input(previous = needHelp, current = null, raisedForPrevious = true, muted = true),
            ),
        )
    }

    @Test
    fun `a member with no status before or after produces nothing`() {
        assertEquals(AlertPolicy.Signal.NONE, AlertPolicy.signalFor(input()))
    }
}
