package `in`.shvms.trackme.domain.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Unit tests for the pure A1 reducer. No Android, no persistence — exercises accumulation,
 * PR detection, idempotency, milestones, Monday week boundaries (incl. a DST zone), and the
 * weekly streak (extend / reset / same-week).
 */
class RideStatsReducerTest {

    private val utc = ZoneId.of("UTC")
    private val ny = ZoneId.of("America/New_York") // DST zone for boundary checks

    /** Epoch millis for a given date + time in a zone. */
    private fun millis(date: LocalDate, time: LocalTime = LocalTime.NOON, zone: ZoneId = utc): Long =
        date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    private fun summary(id: Long, at: Long, durationMs: Long = 60_000L, distM: Double = 1000.0) =
        GoodRideSummary(rideId = id, finishedAtMillis = at, durationMillis = durationMs, distanceMeters = distM)

    @Test
    fun firstRide_isFirstRide_noPr_streakStartsAtOne() {
        val (stats, t) = RideStatsReducer.reduce(
            RideStats(), summary(1, millis(LocalDate.of(2026, 7, 20))), utc // Monday
        )
        assertTrue(t.isFirstRide)
        assertFalse(t.isDistancePR)
        assertFalse(t.isDurationPR)
        assertEquals(1, t.totalRides)
        assertEquals(1, t.streakWeeks)
        assertTrue(t.isFirstRideOfWeek)
        assertTrue(t.streakAdvanced)
        assertEquals(1, stats.totalRides)
        assertEquals(1000.0, stats.longestDistanceMeters, 0.001)
    }

    @Test
    fun secondRideLonger_setsBothPRs_strictly() {
        var stats = RideStats()
        stats = RideStatsReducer.reduce(stats, summary(1, millis(LocalDate.of(2026, 7, 20)), 60_000, 1000.0), utc).first
        val (_, t) = RideStatsReducer.reduce(
            stats, summary(2, millis(LocalDate.of(2026, 7, 21)), 120_000, 2000.0), utc
        )
        assertFalse(t.isFirstRide)
        assertTrue(t.isDistancePR)
        assertTrue(t.isDurationPR)
    }

    @Test
    fun equalToRecord_isNotAPr_strictGreaterOnly() {
        var stats = RideStats()
        stats = RideStatsReducer.reduce(stats, summary(1, millis(LocalDate.of(2026, 7, 20)), 60_000, 1000.0), utc).first
        val (_, t) = RideStatsReducer.reduce(
            stats, summary(2, millis(LocalDate.of(2026, 7, 21)), 60_000, 1000.0), utc
        )
        assertFalse(t.isDistancePR)
        assertFalse(t.isDurationPR)
    }

    @Test
    fun duplicateRideId_isNoOp() {
        var stats = RideStats()
        val first = RideStatsReducer.reduce(stats, summary(1, millis(LocalDate.of(2026, 7, 20))), utc)
        stats = first.first
        val (after, t) = RideStatsReducer.reduce(stats, summary(1, millis(LocalDate.of(2026, 7, 20))), utc)
        assertTrue(t.alreadyProcessed)
        assertEquals(1, after.totalRides) // unchanged
        assertEquals(stats, after)
    }

    @Test
    fun sampleRide_isExcludedFromEveryAggregate() {
        val before = RideStats()
        val sample = GoodRideSummary(
            rideId = 99L,
            finishedAtMillis = millis(LocalDate.of(2026, 7, 20)),
            durationMillis = 540_000L,
            distanceMeters = 1_931.4,
            isSample = true,
        )

        val (after, transition) = RideStatsReducer.reduce(before, sample, utc)

        assertEquals(before, after)
        assertTrue(transition.alreadyProcessed)
        assertFalse(after.processedRideIds.contains(sample.rideId))
    }

    @Test
    fun milestone_firesExactlyOnThreshold() {
        var stats = RideStats()
        var lastMilestone: Int? = null
        for (i in 1..10) {
            val res = RideStatsReducer.reduce(
                stats, summary(i.toLong(), millis(LocalDate.of(2026, 7, 20).plusDays(i.toLong()))), utc
            )
            stats = res.first
            if (res.second.milestoneRideCount != null) lastMilestone = res.second.milestoneRideCount
        }
        assertEquals(10, lastMilestone)
        // 11th ride is not a milestone
        val (_, t11) = RideStatsReducer.reduce(stats, summary(11, millis(LocalDate.of(2026, 8, 15))), utc)
        assertNull(t11.milestoneRideCount)
    }

    @Test
    fun sameWeek_secondRide_doesNotAdvanceStreak_butIncrementsWeekCount() {
        val monday = LocalDate.of(2026, 7, 20)
        var stats = RideStats()
        stats = RideStatsReducer.reduce(stats, summary(1, millis(monday)), utc).first
        val (_, t) = RideStatsReducer.reduce(stats, summary(2, millis(monday.plusDays(2))), utc) // Wed, same week
        assertFalse(t.isFirstRideOfWeek)
        assertFalse(t.streakAdvanced)
        assertEquals(1, t.streakWeeks)
        assertEquals(2, t.weekRideCount)
    }

    @Test
    fun consecutiveWeeks_extendStreak() {
        val w1 = LocalDate.of(2026, 7, 20) // Monday
        val w2 = w1.plusWeeks(1)
        val w3 = w1.plusWeeks(2)
        var stats = RideStats()
        stats = RideStatsReducer.reduce(stats, summary(1, millis(w1)), utc).first
        stats = RideStatsReducer.reduce(stats, summary(2, millis(w2.plusDays(3))), utc).first
        val (_, t) = RideStatsReducer.reduce(stats, summary(3, millis(w3.plusDays(1))), utc)
        assertEquals(3, t.streakWeeks)
        assertTrue(t.streakAdvanced)
    }

    @Test
    fun singleMissedWeek_isForgiven_byFreeze() {
        // B3: exactly one missed week is auto-frozen — streak survives, freeze token consumed.
        val w1 = LocalDate.of(2026, 7, 20)
        val w3 = w1.plusWeeks(2) // skipped w2 (a single miss)
        var stats = RideStats()
        stats = RideStatsReducer.reduce(stats, summary(1, millis(w1)), utc).first
        val (after, t) = RideStatsReducer.reduce(stats, summary(2, millis(w3)), utc)
        assertEquals(2, t.streakWeeks)      // survived, not reset
        assertTrue(t.streakFroze)
        assertFalse(after.freezeAvailable)  // token consumed
    }

    @Test
    fun twoMissedWeeks_resetStreak() {
        // B3: two+ missed weeks cannot both be frozen -> reset to 1, no freeze consumed.
        val w1 = LocalDate.of(2026, 7, 20)
        val w4 = w1.plusWeeks(3) // skipped w2 and w3
        var stats = RideStats()
        stats = RideStatsReducer.reduce(stats, summary(1, millis(w1)), utc).first
        val (after, t) = RideStatsReducer.reduce(stats, summary(2, millis(w4)), utc)
        assertEquals(1, t.streakWeeks)
        assertFalse(t.streakFroze)
        assertTrue(after.freezeAvailable) // refilled by the new active week
    }

    @Test
    fun twoConsecutiveSingleMisses_secondResets() {
        // First isolated miss forgiven; the next isolated miss has no token left -> reset.
        val w1 = LocalDate.of(2026, 7, 20)
        var stats = RideStats()
        stats = RideStatsReducer.reduce(stats, summary(1, millis(w1)), utc).first
        stats = RideStatsReducer.reduce(stats, summary(2, millis(w1.plusWeeks(2))), utc).first // forgiven -> 2
        val (_, t) = RideStatsReducer.reduce(stats, summary(3, millis(w1.plusWeeks(4))), utc)   // miss again
        assertEquals(1, t.streakWeeks)
        assertFalse(t.streakFroze)
    }

    @Test
    fun activeWeek_refillsFreeze_afterAForgivenMiss() {
        val w1 = LocalDate.of(2026, 7, 20)
        var stats = RideStats()
        stats = RideStatsReducer.reduce(stats, summary(1, millis(w1)), utc).first
        stats = RideStatsReducer.reduce(stats, summary(2, millis(w1.plusWeeks(2))), utc).first // forgiven -> 2, no token
        stats = RideStatsReducer.reduce(stats, summary(3, millis(w1.plusWeeks(3))), utc).first // consecutive -> 3, token refilled
        assertTrue(stats.freezeAvailable)
        val (_, t) = RideStatsReducer.reduce(stats, summary(4, millis(w1.plusWeeks(5))), utc)   // single miss again
        assertEquals(4, t.streakWeeks)
        assertTrue(t.streakFroze) // forgiven again thanks to refilled token
    }

    @Test
    fun weekBoundary_sundayVsMonday_areDifferentWeeks() {
        // 2026-07-19 is a Sunday, 2026-07-20 is a Monday -> different Monday-anchored weeks.
        val sunday = LocalDate.of(2026, 7, 19)
        val monday = LocalDate.of(2026, 7, 20)
        assertEquals(DayOfWeek.SUNDAY, sunday.dayOfWeek)
        val startSun = WeekKey.weekStartEpochDay(millis(sunday, LocalTime.of(23, 0)), utc)
        val startMon = WeekKey.weekStartEpochDay(millis(monday, LocalTime.of(1, 0)), utc)
        assertEquals(7L, startMon - startSun)
    }

    @Test
    fun timezone_affectsWeekAssignment_atMidnightBoundary() {
        // A ride finished just after UTC midnight Monday is still Sunday in New York.
        val mondayMidnightUtc = millis(LocalDate.of(2026, 7, 20), LocalTime.of(0, 30), utc)
        val startUtc = WeekKey.weekStartEpochDay(mondayMidnightUtc, utc)
        val startNy = WeekKey.weekStartEpochDay(mondayMidnightUtc, ny)
        // In NY it's still the previous Monday's week (Sunday evening), 7 days earlier.
        assertEquals(7L, startUtc - startNy)
    }

    @Test
    fun processedIds_areBounded() {
        var stats = RideStats()
        val n = RideStats.MAX_PROCESSED_IDS + 50
        for (i in 1..n) {
            stats = RideStatsReducer.reduce(
                stats, summary(i.toLong(), millis(LocalDate.of(2026, 1, 1).plusDays(i.toLong()))), utc
            ).first
        }
        assertTrue(stats.processedRideIds.size <= RideStats.MAX_PROCESSED_IDS)
        // Most recent id retained; oldest evicted.
        assertTrue(stats.processedRideIds.contains(n.toLong()))
        assertFalse(stats.processedRideIds.contains(1L))
    }

    @Test
    fun weekLabel_isIsoMondayAnchored() {
        val monday = LocalDate.of(2026, 7, 20)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val label = WeekKey.label(monday.toEpochDay())
        assertEquals("2026-W30", label)
    }
}
