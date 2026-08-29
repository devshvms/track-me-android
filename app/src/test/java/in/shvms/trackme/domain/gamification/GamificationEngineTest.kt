package `in`.shvms.trackme.domain.gamification

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GamificationEngineTest {

    private fun loadVectors(): JSONObject {
        val stream = this.javaClass.classLoader?.getResourceAsStream("home-gamification-v1.json") 
            ?: throw IllegalArgumentException("home-gamification-v1.json not found")
        val jsonString = stream.bufferedReader().use { it.readText() }
        return JSONObject(jsonString)
    }

    @Test
    fun `test level vectors`() {
        val vectors = loadVectors()
        val levels = vectors.getJSONArray("levels")
        
        for (i in 0 until levels.length()) {
            val obj = levels.getJSONObject(i)
            val duration = obj.getLong("duration_millis")
            val expectedLevel = obj.getString("expected_level_id")
            
            val facts = GamificationFacts(lifetimeActivityCount = 0, lifetimeActiveDurationMillis = duration)
            val snapshot = GamificationEngine.deriveSnapshot(facts)
            
            assertEquals("Failed for duration $duration", expectedLevel, snapshot.currentLevelId)
        }
    }

    @Test
    fun `test milestone vectors`() {
        val vectors = loadVectors()
        val milestones = vectors.getJSONArray("milestones")
        
        for (i in 0 until milestones.length()) {
            val obj = milestones.getJSONObject(i)
            val count = obj.getInt("activity_count")
            val expectedMilestone = obj.getString("expected_milestone_id")
            
            val facts = GamificationFacts(lifetimeActivityCount = count, lifetimeActiveDurationMillis = 0L)
            val snapshot = GamificationEngine.deriveSnapshot(facts)
            
            if (expectedMilestone == "milestone_none") {
                assertTrue("Expected no milestones for count $count", snapshot.unlockedMilestoneIds.isEmpty())
            } else {
                assertTrue(
                    "Expected milestone $expectedMilestone to be unlocked for count $count, but was ${snapshot.unlockedMilestoneIds}",
                    snapshot.unlockedMilestoneIds.contains(expectedMilestone)
                )
            }
        }
    }

    @Test
    fun `test idempotent output`() {
        val facts = GamificationFacts(lifetimeActivityCount = 27, lifetimeActiveDurationMillis = 50_000_000L)
        val snapshot1 = GamificationEngine.deriveSnapshot(facts)
        val snapshot2 = GamificationEngine.deriveSnapshot(facts)
        
        assertEquals(snapshot1, snapshot2)
    }

    @Test
    fun `test deletion rollback`() {
        val baseFacts = GamificationFacts(lifetimeActivityCount = 25, lifetimeActiveDurationMillis = 36_000_000L) // exactly level 3, milestone 25
        val snapshot = GamificationEngine.deriveSnapshot(baseFacts)
        assertEquals("level_3", snapshot.currentLevelId)
        assertTrue(snapshot.unlockedMilestoneIds.contains("milestone_25"))
        
        // Deletion simulating deleting a ride
        val reducedFacts = GamificationFacts(lifetimeActivityCount = 24, lifetimeActiveDurationMillis = 35_999_999L)
        val rollbackSnapshot = GamificationEngine.deriveSnapshot(reducedFacts)
        
        assertEquals("level_2", rollbackSnapshot.currentLevelId)
        assertTrue(!rollbackSnapshot.unlockedMilestoneIds.contains("milestone_25"))
        assertTrue(rollbackSnapshot.unlockedMilestoneIds.contains("milestone_10"))
    }

    @Test
    fun `test max level`() {
        val facts = GamificationFacts(lifetimeActivityCount = 100, lifetimeActiveDurationMillis = 540_000_000L)
        val snapshot = GamificationEngine.deriveSnapshot(facts)
        assertEquals("level_6", snapshot.currentLevelId)
        assertNull(snapshot.nextLevelDurationThresholdMillis)
    }

    @Test
    fun `test overflow safe`() {
        val hugeFacts = GamificationFacts(lifetimeActivityCount = Int.MAX_VALUE, lifetimeActiveDurationMillis = Long.MAX_VALUE)
        val snapshot = GamificationEngine.deriveSnapshot(hugeFacts)
        assertEquals("level_6", snapshot.currentLevelId)
        assertNull(snapshot.nextLevelDurationThresholdMillis)
        assertTrue(snapshot.unlockedMilestoneIds.contains("milestone_1000"))
    }
    
    @Test
    fun `test deterministic ordering`() {
        val facts = GamificationFacts(lifetimeActivityCount = 2000, lifetimeActiveDurationMillis = 0L)
        val snapshot = GamificationEngine.deriveSnapshot(facts)
        
        // Should be sorted
        val expected = listOf("milestone_1", "milestone_10", "milestone_100", "milestone_1000", "milestone_25", "milestone_250", "milestone_50", "milestone_500").sorted()
        assertEquals(expected, snapshot.unlockedMilestoneIds)
    }
    
    @Test
    fun `test no android framework import enters domain`() {
        val engineFile = File("src/main/java/in/shvms/trackme/domain/gamification/GamificationEngine.kt")
        val modelFile = File("src/main/java/in/shvms/trackme/domain/gamification/GamificationModels.kt")
        val adapterFile = File("src/main/java/in/shvms/trackme/domain/gamification/HomeDashboardGamificationAdapter.kt")
        
        val allLines = engineFile.readLines() + modelFile.readLines() + adapterFile.readLines()
        val hasAndroidImport = allLines.any { it.startsWith("import android.") || it.startsWith("import androidx.") }
        
        assertTrue("Domain should not contain Android framework dependencies", !hasAndroidImport)
    }
}
