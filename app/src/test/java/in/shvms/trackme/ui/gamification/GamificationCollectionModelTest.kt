package `in`.shvms.trackme.ui.gamification

import `in`.shvms.trackme.domain.gamification.GamificationEngine
import `in`.shvms.trackme.domain.gamification.GamificationFacts
import `in`.shvms.trackme.ui.localization.AppStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamificationCollectionModelTest {
    @Test
    fun `catalogues remain numeric and complete`() {
        assertEquals(listOf(0L, 120L, 600L, 1_800L, 4_500L, 9_000L), GamificationEngine.levels.map { it.thresholdMinutes })
        assertEquals(listOf(1, 10, 25, 50, 100, 250, 500, 1_000), GamificationEngine.milestones.map { it.activityCount })
    }

    @Test
    fun `collection lock state comes only from snapshot authority`() {
        val snapshot = GamificationEngine.deriveSnapshot(GamificationFacts(25, 579L * 60_000L))
        assertEquals("level_2", snapshot.currentLevelId)
        assertEquals("milestone_25", snapshot.latestUnlockedMilestoneId)
        assertTrue(snapshot.unlockedMilestoneIds.contains("milestone_25"))
        assertFalse(snapshot.unlockedMilestoneIds.contains("milestone_50"))
    }

    @Test
    fun `first milestone and later counts use approved copy`() {
        val strings = AppStrings()
        assertEquals("First Qualifying Activity", strings.formatMilestone("milestone_1"))
        assertEquals("25 qualifying activities", strings.formatMilestone("milestone_25"))
    }
}
