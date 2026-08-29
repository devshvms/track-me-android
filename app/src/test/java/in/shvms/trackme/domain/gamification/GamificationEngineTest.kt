package `in`.shvms.trackme.domain.gamification

import `in`.shvms.trackme.data.local.entity.PostRideCalculation
import `in`.shvms.trackme.data.local.entity.RideEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamificationEngineTest {

    private fun createRide(
        durationMs: Long = 300_000,
        qualifies: Boolean = true,
        isSample: Boolean = false,
        pendingDelete: Boolean = false,
        wasGroup: Boolean = false,
        groupCount: Int? = null,
        persona: String = "CYCLING",
        distance: Double = 1000.0
    ): RideEntity {
        return RideEntity(
            startTime = System.currentTimeMillis(),
            dashboardActiveDurationMillis = durationMs,
            qualifiesForStats = qualifies,
            isSample = isSample,
            pendingDelete = pendingDelete,
            wasGroupRide = wasGroup,
            groupRiderCount = groupCount,
            persona = persona,
            postRideCalculation = PostRideCalculation(
                maxSpeed = 10f,
                distance = distance,
                avgSpeed = 5f,
                pauseDuration = 0
            )
        )
    }

    @Test
    fun `test ride qualification rules`() {
        // Valid
        assertTrue(GamificationEngine.isQualifyingRide(createRide()))
        
        // Invalid: sample
        assertFalse(GamificationEngine.isQualifyingRide(createRide(isSample = true)))
        
        // Invalid: deleted
        assertFalse(GamificationEngine.isQualifyingRide(createRide(pendingDelete = true)))
        
        // Invalid: < 5 mins
        assertFalse(GamificationEngine.isQualifyingRide(createRide(durationMs = 299_000)))
        
        // Invalid: doesn't qualify for stats
        assertFalse(GamificationEngine.isQualifyingRide(createRide(qualifies = false)))
    }

    @Test
    fun `test group ride qualification rules`() {
        assertFalse(GamificationEngine.isQualifyingGroupRide(createRide(wasGroup = true, groupCount = 1)))
        assertTrue(GamificationEngine.isQualifyingGroupRide(createRide(wasGroup = true, groupCount = 2)))
        assertFalse(GamificationEngine.isQualifyingGroupRide(createRide(wasGroup = false)))
    }

    @Test
    fun `test levels derivation`() {
        val level1Rides = listOf(createRide(durationMs = 60 * 60 * 1000L)) // 60 mins -> Level 1 (Starter)
        assertEquals("Starter", GamificationEngine.calculateLevel(level1Rides).name)

        val level2Rides = listOf(createRide(durationMs = 125 * 60 * 1000L)) // 125 mins -> Level 2 (Moving)
        assertEquals("Moving", GamificationEngine.calculateLevel(level2Rides).name)
        
        val level5Rides = listOf(createRide(durationMs = 4501 * 60 * 1000L)) // 4501 mins -> Level 5 (Enduring)
        assertEquals("Enduring", GamificationEngine.calculateLevel(level5Rides).name)
    }

    @Test
    fun `test achievement unlocks`() {
        val rides = mutableListOf<RideEntity>()
        
        // 1st ride
        rides.add(createRide())
        var achievements = GamificationEngine.getUnlockedAchievements(rides)
        assertTrue(achievements.contains("First Qualifying Activity"))
        assertFalse(achievements.contains("Getting Moving"))

        // Add 4 more rides (total 5)
        repeat(4) { rides.add(createRide(persona = "RUNNING")) }
        achievements = GamificationEngine.getUnlockedAchievements(rides)
        assertTrue(achievements.contains("Getting Moving"))
        
        // Multi-Move requires 3 distinct personas
        rides.add(createRide(persona = "WALKING"))
        achievements = GamificationEngine.getUnlockedAchievements(rides)
        assertTrue(achievements.contains("Multi-Move"))
        
        // Add a group ride with 11 people
        rides.add(createRide(wasGroup = true, groupCount = 11, distance = 100000.0))
        achievements = GamificationEngine.getUnlockedAchievements(rides)
        assertTrue(achievements.contains("Together"))
        assertTrue(achievements.contains("Full Crew"))
        assertTrue(achievements.contains("Distance Together"))
    }
}
