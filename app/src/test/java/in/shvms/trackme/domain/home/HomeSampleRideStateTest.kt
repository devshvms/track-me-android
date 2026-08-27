package `in`.shvms.trackme.domain.home

import `in`.shvms.trackme.data.local.dao.HomeDashboardRideProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * TASK-225. The point of the sibling flag is that it tells the empty card which empty state it is
 * in *without* letting a seeded ride into a single number. If these two ever couple, the fix has
 * traded a copy problem for a data-honesty problem.
 */
class HomeSampleRideStateTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val now = 1_756_000_000_000L

    private fun ride(startedAt: Long) = HomeDashboardRideProjection(
        localId = 1L,
        startedAtEpochMillis = startedAt,
        startZoneId = "UTC",
        personaRaw = "CYCLE",
        distanceMeters = 5_000.0,
        activeDurationMillis = 300_000L,
        avgSpeedMps = 16.6,
        hasRoute = true,
    )

    @Test fun `the selector never sets the sample flag itself`() {
        // It is attached by the repository from its own query; the selector only ever sees
        // qualifying rides, which by construction exclude samples.
        assertFalse(HomeDashboardSelector.select(emptyList(), now, zone).hasSampleRide)
        assertFalse(HomeDashboardSelector.select(listOf(ride(now)), now, zone).hasSampleRide)
    }

    @Test fun `sample-only reads as zero qualifying activities, and the flag does not change that`() {
        val sampleOnly = HomeDashboardSelector.select(emptyList(), now, zone).copy(hasSampleRide = true)

        assertTrue(sampleOnly.hasSampleRide)
        assertEquals(0, sampleOnly.lifetimeActivityCount)
        assertEquals(0.0, sampleOnly.lifetimeDistanceMeters, 0.0)
        assertEquals(0L, sampleOnly.lifetimeActiveDurationMillis)
        assertEquals(0, sampleOnly.currentWeek.activityCount)
        assertEquals("empty", sampleOnly.historyBucket)
    }

    @Test fun `true first run and sample-only differ only by the flag`() {
        val firstRun = HomeDashboardSelector.select(emptyList(), now, zone)
        assertEquals(firstRun, sampleOnlyOf(firstRun).copy(hasSampleRide = false))
        assertFalse(firstRun.hasSampleRide)
    }

    @Test fun `recording a real ride ends the sample-only state without touching the flag`() {
        val withOwnRide = HomeDashboardSelector.select(listOf(ride(now)), now, zone)
            .copy(hasSampleRide = true)

        assertEquals(1, withOwnRide.lifetimeActivityCount)
        assertEquals(5_000.0, withOwnRide.lifetimeDistanceMeters, 0.0)
        // The card is chosen by the count, so the line disappears the moment a real ride exists
        // even though the sample is still in History.
        assertTrue(withOwnRide.hasSampleRide)
    }

    private fun sampleOnlyOf(summary: HomeDashboardSummary) = summary.copy(hasSampleRide = true)
}
