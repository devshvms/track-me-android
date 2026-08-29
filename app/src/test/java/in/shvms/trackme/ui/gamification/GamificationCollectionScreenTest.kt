package `in`.shvms.trackme.ui.gamification

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.shvms.trackme.domain.gamification.GamificationSnapshot
import `in`.shvms.trackme.ui.localization.AppStrings
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GamificationCollectionScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `test collection screen displays ladders`() {
        val strings = AppStrings()
        val snapshot = GamificationSnapshot(
            currentLevelId = "level_3",
            nextLevelDurationThresholdMillis = 108_000_000L,
            unlockedMilestoneIds = listOf("milestone_1", "milestone_10")
        )

        var backClicked = false

        composeTestRule.setContent {
            GamificationCollectionScreen(
                snapshot = snapshot,
                strings = strings,
                onNavigateBack = { backClicked = true }
            )
        }

        // Check if levels are visible
        composeTestRule.onNodeWithText("Regular").assertExists() // Level 3
        composeTestRule.onNodeWithText("Explorer").assertExists() // Level 4
        
        // Check if milestones are visible
        composeTestRule.onNodeWithText("10 Rides").assertExists() // Unlocked
        composeTestRule.onNodeWithText("1000 Rides").assertExists() // Locked

        // Check back navigation
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assertTrue(backClicked)
    }
}
