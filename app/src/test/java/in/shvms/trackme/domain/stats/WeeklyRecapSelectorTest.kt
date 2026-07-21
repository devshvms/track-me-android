package `in`.shvms.trackme.domain.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Unit tests for the pure B2 [WeeklyRecapSelector]: only surfaces a completed, non-empty,
 * un-acknowledged week; silent on zero-ride weeks and while still inside the active week.
 */
class WeeklyRecapSelectorTest {

    private val utc = ZoneId.of("UTC")

    private fun millis(date: LocalDate) =
        date.atTime(LocalTime.NOON).atZone(utc).toInstant().toEpochMilli()

    /** Stats representing "rode N times in the week of [weekMonday]". */
    private fun statsForWeek(weekMonday: LocalDate, rides: Int = 3, streak: Int = 2, shown: Long = 0L) =
        RideStats(
            totalRides = rides,
            currentWeekStartEpochDay = weekMonday.toEpochDay(),
            currentWeekRideCount = rides,
            currentWeekDistanceMeters = 12_000.0,
            streakWeeks = streak,
            lastRecapShownWeekStartEpochDay = shown
        )

    @Test
    fun completedWeek_withRides_producesRecap() {
        val w1 = LocalDate.of(2026, 7, 20)  // Monday
        val now = millis(w1.plusWeeks(1).plusDays(1)) // next week
        val recap = WeeklyRecapSelector.select(statsForWeek(w1), now, utc)!!
        assertEquals("2026-W30", recap.weekKey)
        assertEquals(3, recap.rideCount)
        assertEquals(2, recap.streakWeeks)
        assertEquals(w1.toEpochDay(), recap.weekStartEpochDay)
    }

    @Test
    fun stillInsideActiveWeek_producesNothing() {
        val w1 = LocalDate.of(2026, 7, 20)
        val now = millis(w1.plusDays(3)) // same week
        assertNull(WeeklyRecapSelector.select(statsForWeek(w1), now, utc))
    }

    @Test
    fun neverRode_producesNothing() {
        val now = millis(LocalDate.of(2026, 7, 28))
        assertNull(WeeklyRecapSelector.select(RideStats(), now, utc))
    }

    @Test
    fun zeroRideWeek_producesNothing() {
        val w1 = LocalDate.of(2026, 7, 20)
        val now = millis(w1.plusWeeks(1).plusDays(1))
        val stats = statsForWeek(w1).copy(currentWeekRideCount = 0)
        assertNull(WeeklyRecapSelector.select(stats, now, utc))
    }

    @Test
    fun alreadyAcknowledgedWeek_producesNothing() {
        val w1 = LocalDate.of(2026, 7, 20)
        val now = millis(w1.plusWeeks(1).plusDays(1))
        val stats = statsForWeek(w1, shown = w1.toEpochDay())
        assertNull(WeeklyRecapSelector.select(stats, now, utc))
    }
}
