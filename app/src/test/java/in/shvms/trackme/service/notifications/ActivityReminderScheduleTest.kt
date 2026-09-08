package `in`.shvms.trackme.service.notifications

import `in`.shvms.trackme.domain.notifications.ActivityReminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * SCOPE_1.8.7 §6.1.3 #12a — the schedule arithmetic.
 *
 * These are the tests for the failure that a daily periodic worker had and this replaced: a
 * reminder that is evaluated at the wrong moment does not arrive late, it does not arrive at all.
 */
class ActivityReminderScheduleTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis

    private fun saturday8am() = ActivityReminder.Settings(
        enabled = true, dayOfWeek = 6, hour = 8, minute = 0,
    )

    @Test
    fun `a disabled reminder schedules nothing`() {
        assertNull(
            ActivityReminderSchedule.millisUntilNext(
                saturday8am().copy(enabled = false),
                at(2026, Calendar.SEPTEMBER, 2, 10),
                utc,
            )
        )
    }

    @Test
    fun `an invalid reminder schedules nothing`() {
        assertNull(
            ActivityReminderSchedule.millisUntilNext(
                saturday8am().copy(dayOfWeek = 0),
                at(2026, Calendar.SEPTEMBER, 2, 10),
                utc,
            )
        )
    }

    @Test
    fun `from Wednesday it is three days and change to Saturday morning`() {
        // 2026-09-02 is a Wednesday; 2026-09-05 is the Saturday.
        val delay = ActivityReminderSchedule.millisUntilNext(
            saturday8am(), at(2026, Calendar.SEPTEMBER, 2, 10), utc,
        )!!
        assertEquals(at(2026, Calendar.SEPTEMBER, 5, 8) - at(2026, Calendar.SEPTEMBER, 2, 10), delay)
    }

    @Test
    fun `earlier on the day itself schedules for later the same day`() {
        val delay = ActivityReminderSchedule.millisUntilNext(
            saturday8am(), at(2026, Calendar.SEPTEMBER, 5, 6), utc,
        )!!
        assertEquals(2 * 60 * 60 * 1000L, delay)
    }

    /**
     * The re-arm case. Scheduling zero milliseconds out would fire again immediately, and a
     * reminder that repeats in a loop the moment it lands is worse than one that never arrives.
     */
    @Test
    fun `exactly at the slot schedules a week out, not zero`() {
        val delay = ActivityReminderSchedule.millisUntilNext(
            saturday8am(), at(2026, Calendar.SEPTEMBER, 5, 8), utc,
        )!!
        assertEquals(7 * 24 * 60 * 60 * 1000L, delay)
    }

    @Test
    fun `later on the day itself schedules for next week`() {
        val delay = ActivityReminderSchedule.millisUntilNext(
            saturday8am(), at(2026, Calendar.SEPTEMBER, 5, 9), utc,
        )!!
        assertEquals(at(2026, Calendar.SEPTEMBER, 12, 8) - at(2026, Calendar.SEPTEMBER, 5, 9), delay)
    }

    /**
     * `Calendar.set(DAY_OF_WEEK)` moves within the current week *as that locale defines it*, and
     * locales disagree about which day a week starts on. Under a Sunday-first locale, asking for
     * Saturday on a Sunday lands six days in the past. The delay must always be positive.
     */
    @Test
    fun `the delay is positive under every locale's first day of week`() {
        val previousLocale = Locale.getDefault()
        try {
            listOf(Locale.US, Locale.UK, Locale.FRANCE, Locale.GERMANY, Locale("ar", "SA")).forEach { locale ->
                Locale.setDefault(locale)
                (1..7).forEach { day ->
                    // Walk a whole week of "now"s so every first-day-of-week alignment is covered.
                    (2..8).forEach { nowDay ->
                        val delay = ActivityReminderSchedule.millisUntilNext(
                            saturday8am().copy(dayOfWeek = day),
                            at(2026, Calendar.SEPTEMBER, nowDay, 12),
                            utc,
                        )!!
                        assertTrue(
                            "locale=$locale day=$day now=Sept$nowDay gave a non-positive delay of $delay",
                            delay > 0,
                        )
                        assertTrue(
                            "locale=$locale day=$day now=Sept$nowDay gave a delay beyond a week: $delay",
                            delay <= 7 * 24 * 60 * 60 * 1000L,
                        )
                    }
                }
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `the scheduled instant really is the requested weekday and time`() {
        val now = at(2026, Calendar.SEPTEMBER, 2, 10)
        (1..7).forEach { iso ->
            val delay = ActivityReminderSchedule.millisUntilNext(
                ActivityReminder.Settings(enabled = true, dayOfWeek = iso, hour = 7, minute = 30),
                now,
                utc,
            )!!
            val fireAt = Calendar.getInstance(utc).apply { timeInMillis = now + delay }
            assertEquals(
                "ISO day $iso landed on the wrong weekday",
                iso,
                `in`.shvms.trackme.data.local.RideHistoryProfileSource
                    .isoDayOfWeek(fireAt.get(Calendar.DAY_OF_WEEK)),
            )
            assertEquals(7, fireAt.get(Calendar.HOUR_OF_DAY))
            assertEquals(30, fireAt.get(Calendar.MINUTE))
        }
    }
}
