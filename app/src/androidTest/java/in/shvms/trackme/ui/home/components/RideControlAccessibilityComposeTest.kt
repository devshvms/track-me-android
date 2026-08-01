package `in`.shvms.trackme.ui.home.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import `in`.shvms.trackme.ui.localization.AppStrings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RideControlAccessibilityComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun stopSemanticsActionRequestsStopExactlyOnce() {
        var stopRequests = 0
        composeRule.setContent {
            UnifiedPauseStopPill(
                isPaused = false,
                strings = AppStrings(),
                onPauseToggle = {},
                onStopRide = { stopRequests++ }
            )
        }

        composeRule.onNodeWithContentDescription(AppStrings().stopTracking)
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithContentDescription(AppStrings().stopTracking)
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()

        composeRule.runOnIdle { assertEquals(1, stopRequests) }
    }
}
