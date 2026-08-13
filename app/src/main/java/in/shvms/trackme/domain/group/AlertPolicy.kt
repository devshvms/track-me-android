package `in`.shvms.trackme.domain.group

/**
 * When a member's status is allowed to interrupt everyone else — SCOPE_1.7.2 §3.8, §5.2,
 * amendment **A38**.
 *
 * **An alert that fires wrongly twice is an alert that gets muted forever, and then it is not there
 * on the day it matters.** Every rule below exists to spend that credibility carefully, and they are
 * pure so each one is a test rather than a hope.
 *
 * The haptic is the signal and the visual is the content; they ship together. A buzz with nothing to
 * read is a rider pulling over to find out what happened, which is worse than no buzz.
 */
object AlertPolicy {

    /**
     * §5.2: a latecomer must not be ambushed by every standing status in the group at once.
     *
     * Joining a group where someone said "Need help" ten minutes ago is not news arriving; it is
     * history being read. It belongs in the attention section, not in a heads-up notification.
     */
    const val JOIN_GRACE_MS = 60_000L

    /** What the group should be told about one member, on one sync. */
    enum class Signal {
        /** Nothing. The overwhelming majority of transitions. */
        NONE,

        /** Someone entered severity 1. Heads-up, sound, strong double pulse. */
        ALERT_RAISED,

        /**
         * Someone left severity 1. Quiet — posted, no heads-up, single short pulse.
         *
         * §3.7: an alarm with no resolution is worse than no alarm. It leaves the group riding back
         * to a problem that no longer exists, and it is the fastest way to make people stop trusting
         * the tier.
         */
        ALERT_RESOLVED,
    }

    data class Input(
        val memberUid: String,
        val selfUid: String?,
        /** What this member's status was on the previous sync, as this device saw it. */
        val previous: RiderStatus?,
        val current: RiderStatus?,
        /**
         * Whether this device actually raised the alert for [previous].
         *
         * A resolution is sent only to riders who received the alarm — a "cleared" notification for
         * something you never knew about is pure noise.
         */
        val raisedForPrevious: Boolean,
        /** Freshness of the member's *position*, not their status. */
        val senderStale: Boolean,
        val muted: Boolean,
        val millisSinceJoin: Long,
    )

    fun signalFor(input: Input): Signal {
        // Never for your own status. You already know; you tapped it.
        if (input.memberUid == input.selfUid) return Signal.NONE
        if (input.muted) return Signal.NONE

        val wasAlert = input.previous?.isAlert == true
        val isAlert = input.current?.isAlert == true

        if (isAlert && !wasAlert) {
            // Sync is a STATE snapshot, not an event stream. Re-sending the same status every ~10s
            // must not re-alert — this is the bug that gets written if it is not stated somewhere.
            if (input.millisSinceJoin < JOIN_GRACE_MS) return Signal.NONE
            // A severity-1 status attached to a four-minute-old position is history, not news.
            // Render it; do not interrupt for it.
            if (input.senderStale) return Signal.NONE
            return Signal.ALERT_RAISED
        }

        if (wasAlert && !isAlert) {
            // Includes dropping to a lower tier, not just clearing: the group needs to know the
            // emergency ended either way, and §5.2 alerts only on transitions *into* severity 1.
            return if (input.raisedForPrevious) Signal.ALERT_RESOLVED else Signal.NONE
        }

        return Signal.NONE
    }
}
