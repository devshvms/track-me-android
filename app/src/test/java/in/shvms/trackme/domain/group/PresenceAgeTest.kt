package `in`.shvms.trackme.domain.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SCOPE_1.7.2 §4.3 — the age contract, and the regression net for the §6.3 clock-skew defect.
 *
 * The skew cases are the point of this file. Shipped 1.7.0/1.7.1 compare a relay-stamped timestamp
 * against `System.currentTimeMillis()` on the receiver, so a phone with a wrong clock misreports the
 * whole group's freshness. These tests fail against that implementation and pass against A32's.
 */
class PresenceAgeTest {

    private val serverNow = 1_785_000_000_000L
    private val elapsed = 500_000L
    private val interval = 10

    // --- Position ages --------------------------------------------------------------------------

    @Test
    fun `a position age is the difference between the two server-stamped values`() {
        val anchor = PresenceAge.anchorPosition(serverNow, serverNow - 8_000L, elapsed)
        assertEquals(8_000L, anchor.ageAtReceiptMillis)
        assertEquals(8_000L, PresenceAge.currentAgeMillis(anchor, elapsed))
    }

    @Test
    fun `a position stamped fractionally after the server read its clock is fresh, not future`() {
        val anchor = PresenceAge.anchorPosition(serverNow, serverNow + 250L, elapsed)
        assertEquals(0L, anchor.ageAtReceiptMillis)
    }

    @Test
    fun `an age advances on the receiver's monotonic clock between syncs`() {
        val anchor = PresenceAge.anchorPosition(serverNow, serverNow - 5_000L, elapsed)
        assertEquals(35_000L, PresenceAge.currentAgeMillis(anchor, elapsed + 30_000L))
    }

    @Test
    fun `a monotonic clock that appears to go backwards cannot rewind an age`() {
        val anchor = PresenceAge.anchorPosition(serverNow, serverNow - 5_000L, elapsed)
        assertEquals(5_000L, PresenceAge.currentAgeMillis(anchor, elapsed - 10_000L))
    }

    // --- Skew: the §6.3 defect 2 regression net -------------------------------------------------

    @Test
    fun `a receiver whose wall clock is ten minutes wrong reports the same age either way`() {
        // The whole contract: no device wall clock is consulted, so it cannot matter what either
        // device believes the time is. Both "devices" here anchor from identical server values.
        val serverTs = serverNow - 12_000L

        val behind = PresenceAge.anchorPosition(serverNow, serverTs, elapsed)
        val ahead = PresenceAge.anchorPosition(serverNow, serverTs, elapsed)

        assertEquals(12_000L, PresenceAge.currentAgeMillis(behind, elapsed))
        assertEquals(12_000L, PresenceAge.currentAgeMillis(ahead, elapsed))
    }

    @Test
    fun `a sender whose wall clock is wrong cannot distort their own status age`() {
        // stAge is a monotonic DURATION from the sender, never a wall-clock instant, so a sender
        // seven hours off still reports "held for 420 seconds" correctly.
        val anchor = PresenceAge.anchorStatus(
            serverNowMillis = serverNow,
            serverTsMillis = serverNow - 2_000L,
            stAgeSeconds = 420L,
            receivedAtElapsed = elapsed,
        )
        assertEquals(422_000L, anchor.ageAtReceiptMillis)
    }

    @Test
    fun `a wall-clock change mid-session does not move any age`() {
        val anchor = PresenceAge.anchorStatus(serverNow, serverNow, 60L, elapsed)
        val before = PresenceAge.currentAgeMillis(anchor, elapsed + 5_000L)
        // Nothing in the API accepts a wall clock, so a change simply has nowhere to enter.
        val after = PresenceAge.currentAgeMillis(anchor, elapsed + 5_000L)
        assertEquals(before, after)
    }

    // --- The reboot rule (§4.3) -----------------------------------------------------------------

    @Test
    fun `a status whose sender rebooted keeps the status and drops the age`() {
        val anchor = PresenceAge.anchorStatus(serverNow, serverNow - 1_000L, null, elapsed)
        assertEquals(PresenceAge.Bucket.Unknown, PresenceAge.bucket(anchor, elapsed, interval))
    }

    @Test
    fun `an unknown age never becomes a fabricated number as time passes`() {
        val anchor = PresenceAge.anchorStatus(serverNow, serverNow, null, elapsed)
        assertEquals(
            PresenceAge.Bucket.Unknown,
            PresenceAge.bucket(anchor, elapsed + 3_600_000L, interval),
        )
    }

    @Test
    fun `a negative stAge from a broken sender clamps rather than reading as the future`() {
        val anchor = PresenceAge.anchorStatus(serverNow, serverNow, -99L, elapsed)
        assertEquals(0L, anchor.ageAtReceiptMillis)
    }

    // --- Buckets (§2.2) -------------------------------------------------------------------------

    @Test
    fun `anything fresher than one sync interval reads as Now, not a ticking number`() {
        assertEquals(PresenceAge.Bucket.Now, PresenceAge.bucketOf(0L, interval))
        assertEquals(PresenceAge.Bucket.Now, PresenceAge.bucketOf(9_999L, interval))
    }

    @Test
    fun `the Now threshold tracks the relay's advertised cadence, not a constant`() {
        // §7.2 of 1.7.0: the relay slows everyone down under load. A fixed threshold would make the
        // whole fleet start counting seconds during a legitimate slowdown.
        assertEquals(PresenceAge.Bucket.Now, PresenceAge.bucketOf(25_000L, 30))
        assertEquals(PresenceAge.Bucket.Seconds(25), PresenceAge.bucketOf(25_000L, 10))
    }

    @Test
    fun `buckets step at their boundaries`() {
        assertEquals(PresenceAge.Bucket.Seconds(10), PresenceAge.bucketOf(10_000L, interval))
        assertEquals(PresenceAge.Bucket.Seconds(59), PresenceAge.bucketOf(59_999L, interval))
        assertEquals(PresenceAge.Bucket.Minutes(1), PresenceAge.bucketOf(60_000L, interval))
        assertEquals(PresenceAge.Bucket.Minutes(59), PresenceAge.bucketOf(3_599_999L, interval))
        assertEquals(PresenceAge.Bucket.Hours(1), PresenceAge.bucketOf(3_600_000L, interval))
        assertEquals(PresenceAge.Bucket.Hours(3), PresenceAge.bucketOf(3 * 3_600_000L + 5_000L, interval))
    }

    @Test
    fun `a negative age is impossible rather than rendered`() {
        assertEquals(PresenceAge.Bucket.Now, PresenceAge.bucketOf(-5_000L, interval))
    }

    @Test
    fun `a zero or absurd sync interval does not divide by zero or swallow everything`() {
        assertEquals(PresenceAge.Bucket.Seconds(30), PresenceAge.bucketOf(30_000L, 0))
        assertEquals(PresenceAge.Bucket.Seconds(30), PresenceAge.bucketOf(30_000L, -5))
    }

    // --- Rollout fallback -----------------------------------------------------------------------

    @Test
    fun `the pre-serverNow fallback degrades to the old behaviour rather than showing nothing`() {
        // Exists only for a 1.7.2 client against a not-yet-deployed relay. It is the defective
        // behaviour by definition; the point is that it is explicit and deletable.
        val anchor = PresenceAge.anchorPositionWithoutServerNow(serverNow, serverNow - 3_000L, elapsed)
        assertEquals(3_000L, anchor.ageAtReceiptMillis)
        assertTrue(anchor.isKnown)
    }
}
