package `in`.shvms.trackme.domain.group

/**
 * What Home tells a rider about their own presence in the group — SCOPE_1.7.2 §3.6, §4.5,
 * amendments **A28** and **A29**.
 *
 * Today a rider in a live group whose network dies sees a **green** "Offline Shield Active" pill:
 * correct about the ride, badly wrong about the group, and reassuring at the exact moment the group
 * stopped receiving their updates. This decides what replaces it.
 *
 * **The pill is composed, not selected.** A strict precedence table gets this wrong in a way that
 * would have shipped: a rider who is offline *and* has an unacknowledged "Need help" is in **one**
 * situation with **two** consequences, and the second is the one they must know. Ranking "offline"
 * above "not sent" would show `Group updates paused` and never tell them their alert did not go out.
 *
 * Pure, so the rules that decide whether a rider learns they have gone dark are testable without a
 * network, a relay, or a phone.
 */
object GroupPresencePolicy {

    /**
     * Why the last sync failed. This selects the pill's **explanation only** — never whether there
     * is a problem.
     *
     * `NET_CAPABILITY_VALIDATED` flaps on captive portals and marginal cell, and a pill that strobes
     * through patchy coverage is worse than no pill. The failure is defined by the relay having gone
     * unanswered for long enough (§4.5), which is the fact that actually matters; connectivity state
     * only names the cause so the rider knows whether to check their signal or simply wait.
     */
    enum class FailureKind { NO_INTERNET, SERVICE_UNAVAILABLE, AUTH, PROTOCOL }

    /** Which sentence the group-state clause uses. */
    enum class Cause {
        /** The rider's own connection. "Check your signal." */
        LOCAL,

        /** Ours. "Wait." Blaming the rider's phone for our outage is dishonest, and so is the reverse. */
        RELAY,
    }

    /**
     * One pill, or none. These map 1:1 onto §3.6's resulting-pill table, which is what the UI renders
     * and what the tests assert.
     */
    sealed interface Pill {
        data object None : Pill

        /**
         * Healthy, with a status set. Answers the question Home cannot otherwise answer — *"am I
         * still showing Engine heat from forty minutes ago?"* — which is the real hazard of a sticky
         * status (§2.4).
         */
        data class StatusReminder(val status: RiderStatus, val age: PresenceAge.Bucket) : Pill

        /** Healthy, but the relay has not acknowledged the status yet. Never claims delivery. */
        data class StatusUnsent(val status: RiderStatus) : Pill

        /**
         * Group exchange is paused. [rideRecording] gates the reassurance clause, because it is only
         * true when a ride is actually running.
         */
        data class Paused(
            val cause: Cause,
            val rideRecording: Boolean,
            val lastShared: PresenceAge.Bucket,
        ) : Pill

        /**
         * Paused **and** a severity-1 status has not been delivered. The composed case, and the
         * reason this is not a precedence table: the rider must learn the alert did not go out.
         */
        data class PausedWithUnsentAlert(
            val cause: Cause,
            val rideRecording: Boolean,
            val status: RiderStatus,
        ) : Pill

        /**
         * §8 of 1.7.0's revoked-permission banner, now also on Home. [status] is non-null when the
         * rider has said something despite not sharing a position — which §4.7 deliberately allows,
         * and which is the whole reason status has its own slot.
         */
        data class NotSharing(
            val status: RiderStatus?,
            /** Meaningful only when [status] is non-null; false otherwise, never a claim about nothing. */
            val statusAcknowledged: Boolean,
        ) : Pill
    }

    data class Input(
        val sessionActive: Boolean,
        /**
         * `SystemClock.elapsedRealtime()` when this session became active.
         *
         * The reference point when [lastSuccessfulSyncElapsed] is null. Without it, a rider who
         * joined a group while the relay was already down would sit at "everything is fine"
         * indefinitely, because there was never a success to measure staleness from — the one case
         * where the pill is most obviously owed.
         */
        val sessionStartedElapsed: Long,
        /** `SystemClock.elapsedRealtime()` of the last valid authenticated sync response. */
        val lastSuccessfulSyncElapsed: Long?,
        /** When the relay last accepted a **new position**. Not the same fact as a successful sync. */
        val lastOwnPositionAckElapsed: Long?,
        val lastFailureKind: FailureKind?,
        val isSharingPosition: Boolean,
        val isRideRecording: Boolean,
        val selfStatus: RiderStatus?,
        val selfStatusAcknowledged: Boolean,
        val syncIntervalSec: Int,
        val nowElapsed: Long,
    )

    /**
     * §4.5: one **non-overlapping** entry threshold.
     *
     * The draft had `HEALTHY` below 2× interval but entered failure at 1.5×, which is two states
     * claiming the same range. The threshold tracks the *server-advertised* interval because the
     * relay slows everyone down under load (§7.2 of 1.7.0) — a fixed number would light up the whole
     * fleet during a legitimate slowdown.
     */
    const val MIN_PAUSE_THRESHOLD_MS = 30_000L

    fun pauseThresholdMillis(syncIntervalSec: Int): Long =
        maxOf(MIN_PAUSE_THRESHOLD_MS, syncIntervalSec.coerceAtLeast(1) * 2L * 1000L)

    fun evaluate(input: Input): Pill {
        if (!input.sessionActive) return Pill.None

        // Not sharing outranks everything: a rider who believes they are visible when they are not
        // is "the single worst way for this feature to be wrong" (§8 of 1.7.0).
        if (!input.isSharingPosition) {
            return Pill.NotSharing(
                status = input.selfStatus,
                statusAcknowledged = input.selfStatus != null && input.selfStatusAcknowledged,
            )
        }

        val paused = isPaused(input)
        val alertUndelivered =
            input.selfStatus?.isAlert == true && !input.selfStatusAcknowledged

        if (paused) {
            val cause = if (input.lastFailureKind == FailureKind.NO_INTERNET) Cause.LOCAL else Cause.RELAY
            // The composed case. An undelivered alert is the most consequential true thing on
            // screen, so it displaces the "last shared" clause rather than queueing behind it.
            if (alertUndelivered) {
                return Pill.PausedWithUnsentAlert(cause, input.isRideRecording, input.selfStatus!!)
            }
            return Pill.Paused(
                cause = cause,
                rideRecording = input.isRideRecording,
                lastShared = lastSharedBucket(input),
            )
        }

        val status = input.selfStatus ?: return Pill.None
        if (!input.selfStatusAcknowledged) return Pill.StatusUnsent(status)
        return Pill.StatusReminder(status, lastSharedBucket(input))
    }

    /**
     * Entered on the threshold, and **exited only by a successful authenticated sync** — a rider who
     * is back must be told at once, so there is no symmetric cool-down here.
     *
     * A session that has never synced measures from when it started, not from nothing: joining a
     * group whose relay is already down is exactly when a rider needs telling, and an early return
     * here would have left them silently believing they were visible.
     */
    private fun isPaused(input: Input): Boolean {
        val reference = input.lastSuccessfulSyncElapsed ?: input.sessionStartedElapsed
        return (input.nowElapsed - reference) >= pauseThresholdMillis(input.syncIntervalSec)
    }

    /**
     * "Last shared" reads from the **position ack**, not the sync.
     *
     * A successful receive-only sync proves the device can talk to the relay; it does not prove the
     * relay accepted a new position. A rider with a frozen GPS but a healthy network would otherwise
     * see a fresh "last shared" — precisely backwards, and the case that forced these two facts
     * apart (§4.4).
     */
    private fun lastSharedBucket(input: Input): PresenceAge.Bucket {
        val ack = input.lastOwnPositionAckElapsed ?: return PresenceAge.Bucket.Unknown
        return PresenceAge.bucketOf(input.nowElapsed - ack, input.syncIntervalSec)
    }
}
