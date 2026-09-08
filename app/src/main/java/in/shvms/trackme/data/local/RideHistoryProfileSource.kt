package `in`.shvms.trackme.data.local

import `in`.shvms.trackme.data.local.dao.RideDao
import `in`.shvms.trackme.data.local.dao.RideHistoryRow
import `in`.shvms.trackme.domain.notifications.RideHistoryProfile
import java.util.Calendar
import java.util.TimeZone

/**
 * SCOPE_1.8.7 §6.1.3 #12a / §6.1.2 #10b — the boundary where timestamps become weekdays.
 *
 * [RideHistoryProfile] deliberately takes a weekday and an hour rather than a timestamp, because a
 * policy that reaches for the device clock cannot be tested at the boundaries that matter. This is
 * the other side of that decision: the one place that owns a `Calendar`, and therefore the one
 * place a timezone bug can live.
 *
 * ### The weekday numbering is the trap
 *
 * `Calendar.SUNDAY` is **1** and `Calendar.MONDAY` is **2**. The vectors are ISO-8601, where Monday
 * is 1 and Sunday is 7. Passing `Calendar.DAY_OF_WEEK` through unconverted shifts every suggestion
 * by a day and shows a Saturday rider "Friday" — a wrong answer that looks like a plausible one,
 * which is the worst kind. Foundation numbers weekdays the same way as `Calendar`, so iOS has the
 * identical conversion and the identical trap.
 */
object RideHistoryProfileSource {

    /**
     * Reads recent history and converts it into policy samples.
     *
     * Rides are bucketed in the device's **current** timezone rather than the one they were
     * recorded in. That is the right choice for a suggestion: someone who has moved wants a
     * reminder in the mornings they are now living, not the mornings they used to.
     */
    suspend fun samples(
        dao: RideDao,
        timeZone: TimeZone = TimeZone.getDefault(),
        limit: Int = 60,
    ): List<RideHistoryProfile.Sample> = dao.recentHistorySamples(limit).map { it.toSample(timeZone) }

    fun RideHistoryRow.toSample(timeZone: TimeZone): RideHistoryProfile.Sample {
        val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = startTime }
        return RideHistoryProfile.Sample(
            dayOfWeek = isoDayOfWeek(calendar.get(Calendar.DAY_OF_WEEK)),
            hour = calendar.get(Calendar.HOUR_OF_DAY),
            activeMinutes = dashboardActiveDurationMillis / 60_000L,
            persona = persona,
        )
    }

    /** `Calendar`'s Sunday-first numbering to ISO-8601's Monday-first. */
    fun isoDayOfWeek(calendarDayOfWeek: Int): Int =
        if (calendarDayOfWeek == Calendar.SUNDAY) 7 else calendarDayOfWeek - 1

    /** The inverse, for scheduling against a `Calendar` from a stored ISO weekday. */
    fun calendarDayOfWeek(isoDayOfWeek: Int): Int =
        if (isoDayOfWeek == 7) Calendar.SUNDAY else isoDayOfWeek + 1
}
