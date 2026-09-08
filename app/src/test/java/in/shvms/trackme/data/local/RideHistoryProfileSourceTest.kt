package `in`.shvms.trackme.data.local

import `in`.shvms.trackme.data.local.RideHistoryProfileSource.toSample
import `in`.shvms.trackme.data.local.dao.RideHistoryRow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The weekday conversion, which is the one place a plausible-looking wrong answer can be produced.
 *
 * `Calendar.SUNDAY` is 1 and the vectors are ISO-8601, where Sunday is 7. An unconverted value
 * shifts every suggestion by a day and offers a Saturday rider "Friday" — which reads as a real
 * suggestion rather than as a bug, and so would survive a manual look.
 */
class RideHistoryProfileSourceTest {

    @Test
    fun `Calendar weekdays convert to ISO weekdays`() {
        assertEquals(1, RideHistoryProfileSource.isoDayOfWeek(Calendar.MONDAY))
        assertEquals(2, RideHistoryProfileSource.isoDayOfWeek(Calendar.TUESDAY))
        assertEquals(3, RideHistoryProfileSource.isoDayOfWeek(Calendar.WEDNESDAY))
        assertEquals(4, RideHistoryProfileSource.isoDayOfWeek(Calendar.THURSDAY))
        assertEquals(5, RideHistoryProfileSource.isoDayOfWeek(Calendar.FRIDAY))
        assertEquals(6, RideHistoryProfileSource.isoDayOfWeek(Calendar.SATURDAY))
        assertEquals(7, RideHistoryProfileSource.isoDayOfWeek(Calendar.SUNDAY))
    }

    @Test
    fun `the conversion round-trips`() {
        (1..7).forEach { iso ->
            assertEquals(
                iso,
                RideHistoryProfileSource.isoDayOfWeek(RideHistoryProfileSource.calendarDayOfWeek(iso)),
            )
        }
    }

    @Test
    fun `a Saturday morning ride becomes ISO day 6`() {
        val utc = TimeZone.getTimeZone("UTC")
        val saturday0800 = Calendar.getInstance(utc).apply {
            clear()
            set(2026, Calendar.SEPTEMBER, 5, 8, 30, 0) // a Saturday
        }.timeInMillis

        val sample = RideHistoryRow(
            startTime = saturday0800,
            dashboardActiveDurationMillis = 40 * 60_000L,
            persona = "RUN",
        ).toSample(utc)

        assertEquals(6, sample.dayOfWeek)
        assertEquals(8, sample.hour)
        assertEquals(40L, sample.activeMinutes)
        assertEquals("RUN", sample.persona)
    }

    /**
     * The same instant is a different weekday either side of the date line. Bucketing in the
     * device's current zone is the deliberate choice — a rider who has moved wants a reminder in
     * the mornings they are now living — and this pins that it is actually happening.
     */
    @Test
    fun `rides are bucketed in the supplied timezone, not UTC`() {
        // 2026-09-05 23:30 UTC is Saturday in London and already Sunday in Auckland.
        val instant = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, Calendar.SEPTEMBER, 5, 23, 30, 0)
        }.timeInMillis

        val row = RideHistoryRow(instant, 30 * 60_000L, "RUN")
        assertEquals(6, row.toSample(TimeZone.getTimeZone("UTC")).dayOfWeek)
        assertEquals(7, row.toSample(TimeZone.getTimeZone("Pacific/Auckland")).dayOfWeek)
    }

    @Test
    fun `active minutes are whole minutes of moving time`() {
        val row = RideHistoryRow(0L, 90_000L, "RUN")
        assertEquals(1L, row.toSample(TimeZone.getTimeZone("UTC")).activeMinutes)
    }
}
