package `in`.shvms.trackme.domain.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * SCOPE_1.8.9 §13 — the record a ride beat is written down at save because it exists nowhere else
 * afterwards. These pin that the transition still carries it at that moment.
 */
class RevealPersistenceTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    private fun ride(id: Long, meters: Double, minutes: Long, day: Int) = GoodRideSummary(
        rideId = id,
        finishedAtMillis = ZonedDateTime.of(2026, 9, day, 8, 0, 0, 0, zone).toInstant().toEpochMilli(),
        durationMillis = minutes * 60_000L,
        distanceMeters = meters,
    )

    @Test
    fun `the transition carries the records this ride was judged against`() {
        val before = RideStatsReducer.reduce(RideStats(), ride(1, 38_200.0, 90, 1), zone).first
        val (after, transition) = RideStatsReducer.reduce(before, ride(2, 41_700.0, 80, 2), zone)

        assertTrue(transition.isDistancePR)
        assertFalse(transition.isDurationPR)
        assertEquals(38_200.0, transition.previousLongestDistanceMeters, 0.0)
        assertEquals(90 * 60_000L, transition.previousLongestDurationMillis)
        // The store has already moved on — which is exactly why the transition must carry it.
        assertEquals(41_700.0, after.longestDistanceMeters, 0.0)
    }

    @Test
    fun `the previous best is in the unit its kind compares`() {
        val before = RideStatsReducer.reduce(RideStats(), ride(1, 38_200.0, 90, 1), zone).first
        val transition = RideStatsReducer.reduce(before, ride(2, 30_000.0, 120, 2), zone).second

        assertTrue(transition.isDurationPR)
        assertEquals(90 * 60_000.0, previousBestFor(RevealKind.DURATION_PR, transition)!!, 0.0)
        assertEquals(38_200.0, previousBestFor(RevealKind.DISTANCE_PR, transition)!!, 0.0)
        assertNull(previousBestFor(RevealKind.MILESTONE, transition))
        assertNull(previousBestFor(RevealKind.DEFAULT, transition))
    }

    @Test
    fun `a first ride beat no record`() {
        val transition = RideStatsReducer.reduce(RideStats(), ride(1, 5_000.0, 30, 1), zone).second
        assertTrue(transition.isFirstRide)
        assertEquals(0.0, transition.previousLongestDistanceMeters, 0.0)
        assertNull(previousBestFor(RevealKind.FIRST_RIDE, transition))
    }

    @Test
    fun `a replayed ride reports the records as they stand and earns nothing`() {
        val stats = RideStatsReducer.reduce(RideStats(), ride(1, 12_000.0, 40, 1), zone).first
        val replay = RideStatsReducer.reduce(stats, ride(1, 12_000.0, 40, 1), zone).second
        assertTrue(replay.alreadyProcessed)
        assertEquals(12_000.0, replay.previousLongestDistanceMeters, 0.0)
        assertNull(RevealSelector.select(replay))
    }
}
