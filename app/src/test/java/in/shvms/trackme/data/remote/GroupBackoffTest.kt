package `in`.shvms.trackme.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * SCOPE_1.7.0 §6.2 H1 — the retry policy the rest of the app conspicuously lacks.
 *
 * Pure, so it is tested to completion: no network, no clock, no coroutine.
 */
class GroupBackoffTest {

    /** Fixed seed, so the jitter is exercised rather than avoided, and still reproducible. */
    private val seeded = Random(20260808)

    @Test
    fun `no failures means no backoff delay`() {
        assertEquals(0L, GroupBackoff.delayMillis(0))
        assertEquals(0L, GroupBackoff.delayMillis(-1))
    }

    @Test
    fun `delay grows exponentially and then stops at the cap`() {
        // Compared on the un-jittered midpoint: each step should roughly double until the ceiling.
        val midpoints = (1..12).map { attempt ->
            (1..200).map { GroupBackoff.delayMillis(attempt, seeded) }.average()
        }
        for (i in 1 until 6) {
            assertTrue(
                "attempt ${i + 1} did not grow over attempt $i: ${midpoints[i]} vs ${midpoints[i - 1]}",
                midpoints[i] > midpoints[i - 1] * 1.5,
            )
        }
        assertTrue("never reached the cap", midpoints.last() > GroupBackoff.MAX_DELAY_MS * 0.7)
    }

    @Test
    fun `delay never exceeds the cap, even after a very long outage`() {
        // §8 requires the client to keep retrying in DEGRADED rather than give up, so this runs
        // unbounded — an uncapped shift would overflow into a negative delay and busy-loop.
        for (attempt in listOf(1, 10, 32, 63, 64, 100, 10_000, Int.MAX_VALUE)) {
            val delay = GroupBackoff.delayMillis(attempt, seeded)
            assertTrue("attempt $attempt produced $delay", delay >= 0)
            assertTrue(
                "attempt $attempt exceeded the cap: $delay",
                delay <= GroupBackoff.MAX_DELAY_MS * (1 + GroupBackoff.JITTER_FRACTION),
            )
        }
    }

    @Test
    fun `jitter actually spreads the retries`() {
        // Without this every member of a group retries in the same instant — they all failed on
        // the same relay outage at the same moment. That is a self-inflicted thundering herd
        // against a service that is already struggling.
        val delays = (1..500).map { GroupBackoff.delayMillis(5, seeded) }.toSet()
        assertTrue("jitter produced only ${delays.size} distinct delays", delays.size > 100)

        val base = minOf(GroupBackoff.BASE_DELAY_MS shl 4, GroupBackoff.MAX_DELAY_MS)
        val spread = base * GroupBackoff.JITTER_FRACTION
        assertTrue("jitter fell below the band", delays.min() >= base - spread - 1)
        assertTrue("jitter overshot the band", delays.max() <= base + spread + 1)
    }

    @Test
    fun `a removed member and a vanished group are not retried`() {
        // 403 = removed from the group (§5.2), 404 = the group is gone. Retrying either is
        // pointless AND hides a state change the user needs to see.
        assertFalse("403 must not be retried", GroupBackoff.isRetryable(403))
        assertFalse("404 must not be retried", GroupBackoff.isRetryable(404))
        assertFalse("409 must not be retried", GroupBackoff.isRetryable(409))
        assertFalse("400 must not be retried", GroupBackoff.isRetryable(400))
        assertFalse("401 must not be retried by the loop", GroupBackoff.isRetryable(401))
    }

    @Test
    fun `relay outages and transport failures keep retrying`() {
        // §8: "Redis unreachable — back off with jitter, keep the group DEGRADED, keep retrying."
        assertTrue("503 REDIS_UNAVAILABLE must be retried", GroupBackoff.isRetryable(503))
        assertTrue(GroupBackoff.isRetryable(500))
        assertTrue(GroupBackoff.isRetryable(502))
        assertTrue(GroupBackoff.isRetryable(504))
        assertTrue("429 must be retried, after the delay", GroupBackoff.isRetryable(429))
        assertTrue("no response at all must be retried", GroupBackoff.isRetryable(null))
    }

    @Test
    fun `on success the server decides the cadence`() {
        // §4.3 and §7.1: nextSyncInSec is the most important cost lever in the design, and a
        // client that second-guesses it takes that lever away.
        assertEquals(10_000L, GroupBackoff.nextDelayMillis(0, 10))
        assertEquals(20_000L, GroupBackoff.nextDelayMillis(0, 20))
        assertEquals(60_000L, GroupBackoff.nextDelayMillis(0, 60))
        assertEquals(30_000L, GroupBackoff.nextDelayMillis(0, 30))
    }

    @Test
    fun `a missing or nonsensical cadence falls back to the spec default`() {
        assertEquals(GroupBackoff.DEFAULT_SYNC_INTERVAL_SEC * 1000L, GroupBackoff.nextDelayMillis(0, null))
        // Never zero or negative: that would spin the loop as fast as the network allows.
        assertEquals(1_000L, GroupBackoff.nextDelayMillis(0, 0))
        assertEquals(1_000L, GroupBackoff.nextDelayMillis(0, -5))
    }

    @Test
    fun `after a failure the client backs off instead of obeying the last cadence`() {
        // The server did not answer, so its previous instruction is not evidence of anything.
        val afterFailure = GroupBackoff.nextDelayMillis(3, 10, seeded)
        assertTrue("failure path used the server cadence", afterFailure != 10_000L)
        assertTrue(afterFailure >= GroupBackoff.BASE_DELAY_MS)
    }
}
