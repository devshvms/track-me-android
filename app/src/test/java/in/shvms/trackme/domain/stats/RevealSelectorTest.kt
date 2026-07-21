package `in`.shvms.trackme.domain.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the pure B1 [RevealSelector]. Verifies the bounded set, the strict priority
 * (first ride → distance PR → duration PR → milestone → default), the taxonomy mapping, and
 * that idempotent replays surface nothing.
 */
class RevealSelectorTest {

    private fun transition(
        rideId: Long = 1L,
        alreadyProcessed: Boolean = false,
        isFirstRide: Boolean = false,
        isDistancePR: Boolean = false,
        isDurationPR: Boolean = false,
        milestoneRideCount: Int? = null,
        totalRides: Int = 5,
        distanceMeters: Double = 3200.0,
        durationMillis: Long = 900_000L
    ) = RideStatsTransition(
        rideId = rideId,
        alreadyProcessed = alreadyProcessed,
        isFirstRide = isFirstRide,
        isDistancePR = isDistancePR,
        isDurationPR = isDurationPR,
        milestoneRideCount = milestoneRideCount,
        totalRides = totalRides,
        distanceMeters = distanceMeters,
        durationMillis = durationMillis,
        weekKey = "2026-W30",
        weekRideCount = 1,
        weekDistanceMeters = distanceMeters,
        streakWeeks = 1,
        isFirstRideOfWeek = true,
        streakAdvanced = true
    )

    @Test
    fun alreadyProcessed_selectsNothing() {
        assertNull(RevealSelector.select(transition(alreadyProcessed = true)))
    }

    @Test
    fun firstRide_wins_evenOverPrAndMilestone() {
        val r = RevealSelector.select(
            transition(isFirstRide = true, isDistancePR = true, milestoneRideCount = 10)
        )!!
        assertEquals(RevealKind.FIRST_RIDE, r.kind)
        assertEquals("first_ride", r.revealType)
    }

    @Test
    fun distancePr_beats_durationPr_and_milestone() {
        val r = RevealSelector.select(
            transition(isDistancePR = true, isDurationPR = true, milestoneRideCount = 25)
        )!!
        assertEquals(RevealKind.DISTANCE_PR, r.kind)
        assertEquals("pr", r.revealType)
    }

    @Test
    fun durationPr_beats_milestone() {
        val r = RevealSelector.select(
            transition(isDurationPR = true, milestoneRideCount = 50)
        )!!
        assertEquals(RevealKind.DURATION_PR, r.kind)
        assertEquals("pr", r.revealType)
    }

    @Test
    fun milestone_whenNoPr() {
        val r = RevealSelector.select(transition(milestoneRideCount = 100, totalRides = 100))!!
        assertEquals(RevealKind.MILESTONE, r.kind)
        assertEquals("milestone", r.revealType)
        assertEquals(100, r.milestoneRideCount)
    }

    @Test
    fun ordinaryGoodRide_getsDefault_notNull() {
        val r = RevealSelector.select(transition())!!
        assertEquals(RevealKind.DEFAULT, r.kind)
        assertEquals("default", r.revealType)
    }

    @Test
    fun reveal_carriesFactsForCopy() {
        val r = RevealSelector.select(
            transition(rideId = 42L, distanceMeters = 5000.0, durationMillis = 1_800_000L)
        )!!
        assertEquals(42L, r.rideId)
        assertEquals(5000.0, r.distanceMeters, 0.001)
        assertEquals(1_800_000L, r.durationMillis)
    }
}
