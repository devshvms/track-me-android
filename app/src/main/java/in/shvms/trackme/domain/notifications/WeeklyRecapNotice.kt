package `in`.shvms.trackme.domain.notifications

import `in`.shvms.trackme.domain.stats.WeeklyRecap

/**
 * SCOPE_1.8.7 §6.1.2 scenario 8 — the flagship Class C, and scenario 10a folded into it.
 *
 * The recap already exists, is already deduped per week, and is already gated by `CalmMomentGate`.
 * What it is not, today, is reachable: it appears only if you open the app on a calm Monday, which
 * is exactly the population that needs it least.
 *
 * ### Why 10a lives here rather than in its own notification
 *
 * Scenario 10 — *"You're 20 minutes from Explorer"* as its own scheduled nudge — was **cut**. A
 * scheduled push toward a threshold is streak pressure wearing a different hat: the level is
 * measured in lifetime active minutes, so the only action it implies is "go exert yourself now,
 * because the app is counting", which §4.2 N2 rules out.
 *
 * 10a keeps the fact and drops the interruption. The same sentence, attached to something the user
 * already wanted, costs nothing extra and arrives in a message they opted into.
 *
 * Pure so both platforms can hold the same rules and prove them without a device.
 */
object WeeklyRecapNotice {

    /**
     * Whether a recap should be *notified* — a stricter question than whether one exists.
     *
     * @param alreadyNotifiedWeekStart the last week whose recap was notified. The in-app recap has
     *   its own acknowledgement; this is a separate marker on purpose, because seeing a recap in
     *   the app and being interrupted about it are different events and must dedupe separately.
     */
    fun shouldNotify(
        recap: WeeklyRecap?,
        nowMillis: Long,
        lastProactiveSentAtMillis: Long?,
        alreadyNotifiedWeekStart: Long?,
    ): Boolean {
        if (recap == null) return false
        // A zero-ride week is silent. The selector already guarantees this, and it is worth
        // restating: "you did nothing last week" is the exact message §4.2 N2 forbids, and it would
        // arrive automatically every week for anyone who stopped riding.
        if (recap.rideCount <= 0) return false
        if (alreadyNotifiedWeekStart == recap.weekStartEpochDay) return false
        return NotificationBudget.allows(
            klass = NotificationBudget.Klass.PROACTIVE,
            nowMillis = nowMillis,
            lastProactiveSentAtMillis = lastProactiveSentAtMillis,
        )
    }

    /**
     * Scenario 10a: the level-proximity line, or null when there is nothing true to say.
     *
     * @param minutesToNextLevel remaining lifetime active minutes, or null at the maximum level.
     */
    fun proximityLine(minutesToNextLevel: Long?, nextLevelName: String?): ProximityLine? {
        if (minutesToNextLevel == null || nextLevelName.isNullOrBlank()) return null
        // Non-positive means the level was already reached — the reveal covered it, and saying
        // "0 minutes away" is the app failing to notice something the user already did.
        if (minutesToNextLevel <= 0) return null
        // Far enough away that mentioning it is discouraging rather than motivating. A ceiling
        // makes this a "you are nearly there" line rather than a progress bar in prose.
        if (minutesToNextLevel > MAX_MINUTES_WORTH_MENTIONING) return null
        return ProximityLine(minutesToNextLevel, nextLevelName)
    }

    /** Roughly two good rides. Beyond that the fact is true and the sentence is not encouraging. */
    const val MAX_MINUTES_WORTH_MENTIONING = 120L

    data class ProximityLine(val minutes: Long, val levelName: String)
}
