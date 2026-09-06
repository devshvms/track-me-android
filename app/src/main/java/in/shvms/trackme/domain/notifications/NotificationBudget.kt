package `in`.shvms.trackme.domain.notifications

/**
 * SCOPE_1.8.7 §6.0 — the interruption budget.
 *
 * This is the object that makes *"not too many notifications"* testable rather than a matter of
 * taste, which matters because taste loses arguments to growth ideas and a hard cap does not.
 *
 * Every notification belongs to exactly one class:
 *
 * - **[Klass.CONSEQUENTIAL]** (A) — something happened to the user's data or ride that they must
 *   know about. Unlimited, but each is rare by construction: a ride is only auto-finalized once.
 * - **[Klass.REQUESTED]** (B) — the user asked for this one, at a time they chose. Exactly what
 *   they asked for. Rationing a reminder someone set themselves would be the app overruling a
 *   schedule they can see and edit.
 * - **[Klass.PROACTIVE]** (C) — the app decided to speak. **One per seven days, across every C
 *   source combined.** This is where every re-engagement idea in the category lands, and where
 *   every app in the category loses its users' trust.
 * - **[Klass.OPERATOR]** (D) — a person at TrackMe needs to say something operational (§6.3).
 *   Outside the C budget in **both** directions, deliberately.
 *
 * ### Skipping is free
 *
 * [allows] is a pure query and [recordSent] is the only thing that moves the ledger. That
 * separation is the whole "a skipped C is not consumed" property: a recap the budget refuses stays
 * eligible for the rest of its week and lands at the next calm moment. A cap that *lost*
 * notifications instead of deferring them would make one-per-week a real restriction rather than
 * merely an honest one.
 *
 * ### Why D sits outside the budget in both directions
 *
 * A maintenance notice suppressed because a weekly recap went out on Tuesday is an outage nobody
 * was told about. And a broadcast that consumed the C budget would let the operator channel mute
 * the product's own voice for a week — worse, it would make "send a broadcast" a lever on
 * engagement, which is precisely the thing §6.3's promotional ban exists to prevent.
 *
 * Pure, with no Android types, so both platforms can hold it byte for byte and prove it with the
 * same frozen vectors (`app/src/test/resources/notification-budget-v1.json`).
 */
object NotificationBudget {

    /** One Class C notification per seven days, across all C sources combined. */
    const val PROACTIVE_INTERVAL_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

    /** Scenario 13's own cap, on top of the C budget. See [allowsReturnNotice]. */
    const val RETURN_NOTICE_INTERVAL_MILLIS: Long = 90L * 24 * 60 * 60 * 1000

    /**
     * The notification threshold for "you have been away", in days.
     *
     * Deliberately higher than `HomeInsight.Return`'s in-app threshold of 14: interrupting someone
     * is a bigger claim than showing them something once they have already opened the app.
     */
    const val RETURN_NOTICE_MIN_ABSENCE_DAYS: Int = 21

    enum class Klass {
        CONSEQUENTIAL,
        REQUESTED,
        PROACTIVE,
        OPERATOR;

        /** Only Class C is rationed, and only Class C moves the ledger. */
        val spendsProactiveBudget: Boolean get() = this == PROACTIVE
    }

    /**
     * The Class C sources, **in priority order**. Ordinal is the rank; declaration order is the
     * contract, so reordering this enum changes behaviour and should be a deliberate act.
     */
    enum class ProactiveKind {
        /**
         * Scenario 13. Ranked first because it is far rarer — once per 90 days at most — and
         * because the recap it would otherwise lose to is, for someone who has been away, a report
         * of a week in which they did nothing.
         */
        RETURN_AFTER_ABSENCE,

        /**
         * Scenario 8, the flagship C. Carries scenario 10a's level-proximity line, so the level
         * fact reaches the user without an interruption of its own.
         */
        WEEKLY_RECAP,
    }

    /**
     * May a notification of [klass] be sent right now?
     *
     * @param lastProactiveSentAtMillis when a Class C notification was last actually sent, or null
     *   if none ever has been. A fresh install is not owed a week of silence first.
     */
    fun allows(klass: Klass, nowMillis: Long, lastProactiveSentAtMillis: Long?): Boolean {
        if (!klass.spendsProactiveBudget) return true
        val last = lastProactiveSentAtMillis ?: return true
        // A last-sent time in the future is not a reason to send. Clock changes, restores from
        // backup and timezone edits all produce it, and treating it as "long ago" would make a
        // device whose clock jumped backwards emit a proactive notification on every launch until
        // real time caught up.
        if (nowMillis < last) return false
        return nowMillis - last >= PROACTIVE_INTERVAL_MILLIS
    }

    /**
     * The ledger after sending [klass] at [sentAtMillis]. Call this only when a notification was
     * genuinely delivered — a skipped or suppressed C must leave the ledger untouched.
     *
     * Never moves backwards: an out-of-order send on a device whose clock went back must not
     * reopen the week.
     */
    fun recordSent(klass: Klass, sentAtMillis: Long, lastProactiveSentAtMillis: Long?): Long? {
        if (!klass.spendsProactiveBudget) return lastProactiveSentAtMillis
        val last = lastProactiveSentAtMillis ?: return sentAtMillis
        return maxOf(last, sentAtMillis)
    }

    /**
     * The single Class C to send when several are eligible, or null when none are.
     *
     * The losers are **not** consumed — nothing here records anything. They stay eligible for their
     * next window, exactly as `WeeklyRecapSelector` already behaves.
     *
     * Takes a set and returns by declared rank rather than by iteration order, because a `Set` on
     * one platform and an `Array` on the other is precisely how a silent priority divergence
     * arrives.
     */
    fun choose(eligible: Set<ProactiveKind>): ProactiveKind? =
        ProactiveKind.entries.firstOrNull { it in eligible }

    /**
     * Scenario 13's second gate, applied on top of [allows].
     *
     * Rationed twice because a return notice is the most intrusive thing this app may say: it is a
     * message about *not* having done something, which §4.2 N2 otherwise rules out entirely. It
     * survives at all only because it is gain-framed, carries a real fact, and arrives at most once
     * a quarter.
     *
     * @param daysSinceLastActivity days since the user's last recorded activity.
     */
    fun allowsReturnNotice(
        nowMillis: Long,
        lastReturnNoticeAtMillis: Long?,
        daysSinceLastActivity: Int,
    ): Boolean {
        // They came back. The 90-day window may well be open, but there is nothing to say — and
        // saying it anyway is exactly how a return notice becomes a streak reminder.
        if (daysSinceLastActivity < RETURN_NOTICE_MIN_ABSENCE_DAYS) return false
        val last = lastReturnNoticeAtMillis ?: return true
        if (nowMillis < last) return false
        return nowMillis - last >= RETURN_NOTICE_INTERVAL_MILLIS
    }
}
