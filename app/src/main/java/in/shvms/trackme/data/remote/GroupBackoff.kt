package `in`.shvms.trackme.data.remote

import kotlin.math.min
import kotlin.random.Random

/**
 * Retry policy for the group sync loop — SCOPE_1.7.0 §6.2 H1.
 *
 * The audit is blunt about this: *"No HTTP retry or backoff anywhere in the Android app."*
 * `LiveShareManager` drops failed pushes silently and `TrackingService` launches them
 * fire-and-forget, ignoring the `Result`. A sync loop inheriting that shape would hammer the relay
 * through a network blip at the configured cadence and drain the battery doing it — and group sync
 * is already the dominant line in both the Vercel and Redis bills (§7.2).
 *
 * A pure decision type, following the convention the codebase uses for exactly this kind of rule
 * (`LocationStartDecision`, `RideSplitPolicy`, `AutoPausePreference`): testable to completion with
 * no network, no clock, and no coroutine.
 */
object GroupBackoff {

    /** First retry delay. Short enough that a one-off blip is invisible to the user. */
    const val BASE_DELAY_MS = 1_000L

    /**
     * Ceiling. Past this the group is unusable anyway, and §8 requires the client to *keep*
     * retrying in `DEGRADED` rather than give up — so the cap has to stay short enough that
     * recovery is prompt once the relay comes back.
     */
    const val MAX_DELAY_MS = 60_000L

    /**
     * ±25%. Without jitter every member of a group backs off in lockstep — they failed on the
     * same relay outage at the same moment — and they all retry in the same instant, which is a
     * self-inflicted thundering herd against a service that is already struggling.
     */
    const val JITTER_FRACTION = 0.25

    /**
     * Delay before attempt number [consecutiveFailures] (1 = the first retry).
     *
     * Exponential, capped, then jittered. [random] is injected so the spread is testable.
     */
    fun delayMillis(consecutiveFailures: Int, random: Random = Random.Default): Long {
        if (consecutiveFailures <= 0) return 0L
        // shl on a Long overflows past 63 shifts; clamp the exponent before shifting rather than
        // after, or a long outage produces a negative delay.
        val exponent = min(consecutiveFailures - 1, 20)
        val raw = min(BASE_DELAY_MS shl exponent, MAX_DELAY_MS)
        val spread = (raw * JITTER_FRACTION).toLong()
        return (raw - spread + random.nextLong(2 * spread + 1)).coerceAtLeast(0L)
    }

    /**
     * Whether a failed attempt is worth retrying at all.
     *
     * The distinction that matters: a `403` means the caller was removed from the group (§5.2,
     * "departed member keeps polling") and a `404` means the group is gone. Retrying either is
     * pointless and, worse, hides a state change the user needs to see. Everything else —
     * timeouts, `5xx`, and the relay's own `503 REDIS_UNAVAILABLE` — is transient by §8 and must
     * keep retrying while the group sits in `DEGRADED`.
     */
    fun isRetryable(httpStatus: Int?): Boolean = when (httpStatus) {
        null -> true          // transport failure: no response at all
        403, 404, 409 -> false
        400, 401 -> false     // a bad request or a rejected token will not fix itself by repeating
        else -> httpStatus >= 500 || httpStatus == 429
    }

    /**
     * How long to wait before the next sync.
     *
     * On success the **server decides** (§4.3, §7.1) — `nextSyncInSec` is the single most
     * important cost lever in the design, and a client that second-guesses it takes that lever
     * away. On failure the client backs off on its own, because the server did not answer.
     */
    fun nextDelayMillis(
        consecutiveFailures: Int,
        serverNextSyncInSec: Int?,
        random: Random = Random.Default,
    ): Long = if (consecutiveFailures > 0) {
        delayMillis(consecutiveFailures, random)
    } else {
        // A server that says 0 means "stop syncing" (the group ended); the loop handles that
        // before it ever asks for a delay. Anything absent falls back to the spec default.
        ((serverNextSyncInSec ?: DEFAULT_SYNC_INTERVAL_SEC).coerceAtLeast(1)) * 1_000L
    }

    /** Matches the relay's own default; only used when a response omits the field. */
    const val DEFAULT_SYNC_INTERVAL_SEC = 10
}
