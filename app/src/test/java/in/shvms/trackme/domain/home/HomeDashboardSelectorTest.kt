package `in`.shvms.trackme.domain.home

import `in`.shvms.trackme.data.local.dao.HomeDashboardRideProjection
import `in`.shvms.trackme.domain.model.RidePersona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class HomeDashboardSelectorTest {
    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-08-26T12:00:00Z").toEpochMilli()

    private fun ride(
        id: Long,
        at: String,
        persona: RidePersona = RidePersona.CYCLING,
        distance: Double = 1_000.0,
        duration: Long = 600_000L,
        startZoneId: String? = null,
    ) = HomeDashboardRideProjection(
        localId = id,
        startedAtEpochMillis = Instant.parse(at).toEpochMilli(),
        startZoneId = startZoneId,
        personaRaw = persona.name,
        distanceMeters = distance,
        activeDurationMillis = duration,
        avgSpeedMps = if (duration > 0) distance / (duration / 1_000.0) else 0.0,
        hasRoute = true,
    )

    private fun select(vararg rides: HomeDashboardRideProjection) =
        HomeDashboardSelector.select(rides.toList(), now, utc)

    @Test
    fun `empty history has no fabricated stats or insight`() {
        val summary = select()
        assertEquals("empty", summary.historyBucket)
        assertEquals(0, summary.lifetimeActivityCount)
        assertEquals(0, summary.currentWeek.activityCount)
        assertNull(summary.latestActivity)
        assertNull(summary.insight)
    }

    @Test
    fun `one and two activities remain early without comparisons`() {
        val one = ride(1, "2026-08-25T10:00:00Z")
        assertEquals("early", select(one).historyBucket)
        assertNull(select(one).insight)
        assertNull(select(one, ride(2, "2026-08-18T10:00:00Z", RidePersona.WALK)).insight)
    }

    @Test
    fun `latest personal best is never surfaced on Home`() {
        val summary = select(
            ride(3, "2026-08-25T10:00:00Z", distance = 2_000.0),
            ride(2, "2026-08-04T10:00:00Z", distance = 1_500.0),
            ride(1, "2026-07-28T10:00:00Z", distance = 1_200.0),
        )
        assertTrue(summary.insight is HomeInsight.Return)
        assertFalse(summary.insight?.analyticsValue == "personal_best")
    }

    @Test
    fun `return requires fourteen full days and stays positive`() {
        val summary = select(
            ride(3, "2026-08-25T10:00:00Z", RidePersona.RUN),
            ride(2, "2026-08-10T10:00:00Z", RidePersona.WALK),
            ride(1, "2026-08-03T10:00:00Z", RidePersona.AUTO),
        )
        val insight = summary.insight as HomeInsight.Return
        assertEquals(15L, insight.inactiveDays)
        assertEquals(RidePersona.RUN, insight.persona)
    }

    @Test
    fun `partial current week compares only with eligible prior active week`() {
        val summary = select(
            ride(3, "2026-08-25T10:00:00Z", RidePersona.RUN, 1_050.0),
            ride(2, "2026-08-18T10:00:00Z", RidePersona.WALK, 1_000.0),
            // Same prior week but after Tuesday: excluded from Monday-through-Wednesday basis.
            ride(1, "2026-08-20T10:00:00Z", RidePersona.AUTO, 9_000.0),
        )
        val insight = summary.insight as HomeInsight.PeriodComparison
        assertEquals(InsightDirection.STABLE, insight.direction)
        assertEquals(1_000.0, insight.comparisonValue, 0.0)
    }

    @Test
    fun `ten percent deadband boundary is higher and negative is neutral lower`() {
        val higher = select(
            ride(3, "2026-08-25T10:00:00Z", RidePersona.RUN, 1_100.0),
            ride(2, "2026-08-18T10:00:00Z", RidePersona.WALK, 1_000.0),
            ride(1, "2026-08-11T10:00:00Z", RidePersona.AUTO, 500.0),
        ).insight as HomeInsight.PeriodComparison
        assertEquals(InsightDirection.HIGHER, higher.direction)

        val lower = select(
            ride(6, "2026-08-25T10:00:00Z", RidePersona.RUN, 800.0),
            ride(5, "2026-08-18T10:00:00Z", RidePersona.WALK, 1_000.0),
            ride(4, "2026-08-11T10:00:00Z", RidePersona.AUTO, 500.0),
        ).insight as HomeInsight.PeriodComparison
        assertEquals(InsightDirection.LOWER, lower.direction)
    }

    @Test
    fun `zero comparison denominator suppresses percentage fact`() {
        val summary = select(
            ride(3, "2026-08-25T10:00:00Z", RidePersona.RUN, 800.0),
            ride(2, "2026-08-18T10:00:00Z", RidePersona.WALK, 0.0),
            ride(1, "2026-08-11T10:00:00Z", RidePersona.AUTO, 0.0),
        )
        assertFalse(summary.insight is HomeInsight.PeriodComparison)
    }

    @Test
    fun `eight completed active weeks enable four-week average comparison`() {
        val rides = (1L..8L).mapIndexed { index, id ->
            ride(
                id,
                Instant.parse("2026-08-17T10:00:00Z").minusSeconds(index * 7L * 86_400L).toString(),
                RidePersona.entries[index % RidePersona.entries.size],
                distance = when {
                    index < 4 -> 2_000.0
                    index == 6 -> 2_500.0 // earlier AUTO prevents a latest-ride personal best
                    else -> 1_000.0
                },
            )
        }
        val insight = HomeDashboardSelector.select(rides, now, utc).insight as HomeInsight.PeriodComparison
        assertEquals(InsightDirection.HIGHER, insight.direction)
        assertEquals(2_000.0, insight.currentValue, 0.0)
        assertEquals(1_375.0, insight.comparisonValue, 0.0)
    }

    @Test
    fun `dominant persona requires a strict count leader`() {
        val summary = select(
            ride(3, "2026-08-25T10:00:00Z", RidePersona.CYCLING),
            ride(2, "2026-08-24T10:00:00Z", RidePersona.CYCLING),
            ride(1, "2026-08-23T10:00:00Z", RidePersona.WALK),
        )
        val insight = summary.insight as HomeInsight.DominantPersona
        assertEquals(RidePersona.CYCLING, insight.persona)
        assertEquals(2, insight.personaCount)
    }

    @Test
    fun `streak uses exact seven-day adjacency and can start last week`() {
        val summary = select(
            ride(4, "2026-08-18T10:00:00Z"),
            ride(3, "2026-08-11T10:00:00Z"),
            ride(2, "2026-08-04T10:00:00Z"),
            ride(1, "2026-07-21T10:00:00Z"),
        )
        assertEquals(3, summary.displayStreakWeeks)
    }

    @Test
    fun `a qualifying current week extends rather than resets the live streak`() {
        val summary = select(
            ride(4, "2026-08-25T10:00:00Z"),
            ride(3, "2026-08-18T10:00:00Z"),
            ride(2, "2026-08-11T10:00:00Z"),
            ride(1, "2026-08-04T10:00:00Z"),
        )
        assertEquals(4, summary.displayStreakWeeks)
    }

    @Test
    fun `legacy week bucketing honors caller timezone`() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        val sundayUtc = ride(1, "2026-08-23T20:00:00Z") // Monday 01:30 in Kolkata
        val summary = HomeDashboardSelector.select(listOf(sundayUtc), now, kolkata)
        assertEquals(1, summary.currentWeek.activityCount)
        assertTrue(summary.weeklyBuckets.last().activityCount == 1)
    }

    @Test
    fun `persisted ride timezone survives a different current device timezone`() {
        val sundayUtc = ride(
            1,
            "2026-08-23T20:00:00Z",
            startZoneId = "Asia/Kolkata",
        ) // Monday 01:30 at recording, still Sunday in the current UTC fallback.
        val summary = HomeDashboardSelector.select(listOf(sundayUtc), now, utc)
        assertEquals(1, summary.currentWeek.activityCount)
    }

    @Test
    fun `invalid persisted timezone safely keeps legacy fallback semantics`() {
        val sundayUtc = ride(1, "2026-08-23T20:00:00Z", startZoneId = "not-a-zone")
        val summary = HomeDashboardSelector.select(listOf(sundayUtc), now, utc)
        assertEquals(0, summary.currentWeek.activityCount)
    }

    @Test
    fun `streak is omitted once the most recent active week is older than last week`() {
        val summary = select(
            ride(3, "2026-08-11T10:00:00Z"),
            ride(2, "2026-08-04T10:00:00Z"),
            ride(1, "2026-07-28T10:00:00Z"),
        )
        assertEquals(0, summary.displayStreakWeeks)
    }

    @Test
    fun `large history stays metadata-only and deterministic`() {
        val rides = (1L..750L).map { id ->
            ride(
                id = id,
                at = Instant.parse("2026-08-25T10:00:00Z").minusSeconds(id * 3_600L).toString(),
                persona = RidePersona.entries[(id % RidePersona.entries.size).toInt()],
                distance = 1_000.0 + id,
            )
        }
        val summary = HomeDashboardSelector.select(rides, now, utc)
        assertEquals(750, summary.lifetimeActivityCount)
        assertEquals(750_000.0 + (1L..750L).sum(), summary.lifetimeDistanceMeters, 0.0)
        assertEquals(1L, summary.latestActivity?.localId)
    }
}
