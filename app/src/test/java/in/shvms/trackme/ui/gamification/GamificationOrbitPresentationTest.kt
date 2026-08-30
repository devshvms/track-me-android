package `in`.shvms.trackme.ui.gamification

import `in`.shvms.trackme.domain.gamification.GamificationEngine
import `in`.shvms.trackme.domain.gamification.GamificationFacts
import `in`.shvms.trackme.domain.gamification.GamificationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class GamificationOrbitPresentationTest {
    @Test
    fun relativeProgressAndLevelIndex() {
        val snapshot = GamificationEngine.deriveSnapshot(
            GamificationFacts(
                lifetimeActivityCount = 25,
                lifetimeActiveDurationMillis = 900L * 60_000L,
            ),
        )

        assertEquals(2, gamificationOrbitLevelIndex(snapshot))
        assertEquals(0.25f, gamificationOrbitProgress(snapshot), 0.0001f)
    }

    @Test
    fun maximumLevelRendersAsComplete() {
        val snapshot = GamificationEngine.deriveSnapshot(
            GamificationFacts(
                lifetimeActivityCount = 1_000,
                lifetimeActiveDurationMillis = 9_000L * 60_000L,
            ),
        )

        assertEquals(5, gamificationOrbitLevelIndex(snapshot))
        assertEquals(1f, gamificationOrbitProgress(snapshot), 0.0001f)
    }

    @Test
    fun progressClampsMalformedPresentationInput() {
        val snapshot = GamificationSnapshot(
            currentLevelId = "level_2",
            currentLevelNameKey = "Moving",
            currentMinutes = 999,
            currentThresholdMinutes = 120,
            nextThresholdMinutes = 600,
            progressNumeratorMinutes = 900,
            progressDenominatorMinutes = 480,
            latestUnlockedMilestoneId = null,
            unlockedMilestoneIds = emptyList(),
            unlockedMilestoneCount = 0,
        )

        assertEquals(1f, gamificationOrbitProgress(snapshot), 0.0001f)
    }
}
