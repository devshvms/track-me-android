package `in`.shvms.trackme.service.notifications

import `in`.shvms.trackme.data.local.RideHistoryProfileSource
import `in`.shvms.trackme.domain.notifications.ActivityReminder
import java.util.Calendar
import java.util.TimeZone

/**
 * SCOPE_1.8.7 §6.1.3 scenario 12a — when the next reminder is due.
 *
 * ### Why this exists rather than a daily periodic worker
 *
 * The obvious implementation is a `PeriodicWorkRequest` that runs daily and asks "is it Saturday
 * after 08:00 yet". It does not work. A periodic worker runs once per interval at a moment the OS
 * chooses, and nothing stops that moment being 06:00 every day — in which case a Saturday 08:00
 * reminder is evaluated two hours early, declines to fire, and is not looked at again until Sunday.
 * The reminder would simply never arrive, for everyone whose device settled into an early slot.
 *
 * So the schedule is computed here and the work is one-shot with an initial delay, re-armed after
 * each firing. `SCHEDULE_EXACT_ALARM` stays undeclared, so this still lands *within* its window
 * rather than on the minute — but the window is now anchored to the time the user picked rather
 * than to whenever the OS felt like waking us.
 */
object ActivityReminderSchedule {

    /**
     * Milliseconds from [nowMillis] until the next occurrence of the user's slot.
     *
     * Returns null for a disabled or invalid reminder — there is nothing to schedule, and callers
     * treat null as "cancel any pending work".
     *
     * The boundary is strict: a slot at exactly [nowMillis] schedules a week out rather than zero
     * milliseconds away. Re-arming immediately after a firing is the common case, and a
     * zero-delay re-arm would fire again at once.
     */
    fun millisUntilNext(
        settings: ActivityReminder.Settings,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Long? {
        if (!settings.enabled || !settings.isValid) return null

        val next = Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMillis
            set(Calendar.DAY_OF_WEEK, RideHistoryProfileSource.calendarDayOfWeek(settings.dayOfWeek))
            set(Calendar.HOUR_OF_DAY, settings.hour)
            set(Calendar.MINUTE, settings.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // `set(DAY_OF_WEEK)` moves within the *current* week as the calendar defines it, which can
        // land in the past — and, depending on the locale's first day of the week, can even land
        // in the past by six days. Winding forward a week at a time is the only form of this that
        // is correct for every `firstDayOfWeek`.
        while (next.timeInMillis <= nowMillis) {
            next.add(Calendar.DAY_OF_YEAR, 7)
        }

        return next.timeInMillis - nowMillis
    }
}
