package `in`.shvms.trackme.data.local

import android.content.Context

/**
 * SCOPE_1.8.7 §6.0 — where the Class C budget's one piece of state lives.
 *
 * `NotificationBudget` is pure and holds nothing; this is the ledger it reasons about. Deliberately
 * a single timestamp shared by **every** Class C source rather than one per feature: the cap is one
 * proactive notification per week *in total*, and a per-source ledger would quietly become one per
 * week per source, which is how a hard cap turns into a soft one without anybody deciding to change
 * it.
 *
 * Writes happen only when a notification was genuinely delivered. A refused or skipped C leaves
 * this untouched — that is what makes skipping free.
 */
class ProactiveLedger(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** When a Class C notification was last actually sent, or null if none ever has been. */
    val lastProactiveSentAtMillis: Long?
        get() = if (prefs.contains(KEY_LAST_SENT)) prefs.getLong(KEY_LAST_SENT, 0L) else null

    /** The last time a return-after-absence notice was sent — scenario 13's second gate. */
    val lastReturnNoticeAtMillis: Long?
        get() = if (prefs.contains(KEY_LAST_RETURN)) prefs.getLong(KEY_LAST_RETURN, 0L) else null

    /** The last completed week whose recap was notified, so a week is never announced twice. */
    val lastRecapWeekStartEpochDay: Long?
        get() = if (prefs.contains(KEY_LAST_RECAP_WEEK)) prefs.getLong(KEY_LAST_RECAP_WEEK, 0L) else null

    /**
     * Records a delivered Class C notification. Routed through `NotificationBudget.recordSent` so
     * the "never moves backwards" rule lives in the tested pure object rather than being restated
     * here, where it would be one edit away from being lost.
     */
    fun recordProactiveSent(sentAtMillis: Long) {
        val updated = `in`.shvms.trackme.domain.notifications.NotificationBudget.recordSent(
            klass = `in`.shvms.trackme.domain.notifications.NotificationBudget.Klass.PROACTIVE,
            sentAtMillis = sentAtMillis,
            lastProactiveSentAtMillis = lastProactiveSentAtMillis,
        ) ?: return
        prefs.edit().putLong(KEY_LAST_SENT, updated).apply()
    }

    fun recordReturnNoticeSent(sentAtMillis: Long) {
        prefs.edit().putLong(KEY_LAST_RETURN, sentAtMillis).apply()
    }

    fun recordRecapNotified(weekStartEpochDay: Long) {
        prefs.edit().putLong(KEY_LAST_RECAP_WEEK, weekStartEpochDay).apply()
    }

    private companion object {
        const val PREFS = "trackme_proactive_ledger"
        const val KEY_LAST_SENT = "last_proactive_sent_at"
        const val KEY_LAST_RETURN = "last_return_notice_at"
        const val KEY_LAST_RECAP_WEEK = "last_recap_week_start_epoch_day"
    }
}
