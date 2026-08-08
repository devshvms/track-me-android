package `in`.shvms.trackme.domain.group

/**
 * The optional scheduled start time — SCOPE_1.7.0 **D6, D8, D9**.
 *
 * D9 is an **invariant**, not a preference, and it is the reason this type exists as pure logic
 * rather than as a scheduling call buried in a ViewModel:
 *
 * > *"At the scheduled time the app fires a **reminder**. The leader still presses start, and each
 * > member's location only leaves their device once they have the app open and have consented.
 * > Auto-broadcasting someone's location at a calendar time, without them present, is unacceptable
 * > and must never be introduced as a convenience."*
 *
 * So this decides **when to remind**, and has no ability to start anything. There is no code path
 * from here to `startGroup()` or to presence, and [neverAutoStarts] exists so that stays true.
 */
object GroupStartReminder {

    /** §2.3: "We'll remind you 15 minutes before." */
    const val LEAD_MINUTES = 15

    sealed interface Decision {
        /** Fire a local notification at [atEpochMillis]. */
        data class Schedule(val atEpochMillis: Long) : Decision

        /** Nothing to schedule — no start time, or the moment has already passed. */
        data object None : Decision
    }

    /**
     * @param startAtEpochMillis the leader's chosen start time, or null if they set none (D6 —
     *   both start time and destination are optional, and the minimum path stays create → share →
     *   join → go)
     */
    fun decide(startAtEpochMillis: Long?, nowEpochMillis: Long): Decision {
        if (startAtEpochMillis == null || startAtEpochMillis <= 0L) return Decision.None
        val remindAt = startAtEpochMillis - LEAD_MINUTES * 60_000L

        // Already past the reminder point. Firing immediately would be noise — the user is either
        // already here or the start time is behind them.
        if (remindAt <= nowEpochMillis) return Decision.None

        return Decision.Schedule(remindAt)
    }

    /**
     * A start time in the past is not an error, and must not block creation.
     *
     * §8: *"Scheduled start time arrives, nobody has the app open → a local reminder fires.
     * Nothing starts, nothing broadcasts. The group waits for the leader."* A time already gone is
     * the same case with the reminder skipped.
     */
    fun isUsableStartTime(startAtEpochMillis: Long?, expiresAtEpochMillis: Long): Boolean {
        if (startAtEpochMillis == null) return true
        // A start time after the group has already expired is the one genuinely nonsensical case.
        return startAtEpochMillis < expiresAtEpochMillis
    }

    /**
     * Present so the D9 invariant is asserted by a test rather than trusted to a comment.
     *
     * This module has no reference to the session manager, the tracking service, or presence, and
     * a test reads this file's source to keep it that way. If a future change wires "start
     * automatically at the scheduled time" through here, it fails.
     */
    const val neverAutoStarts: Boolean = true
}
