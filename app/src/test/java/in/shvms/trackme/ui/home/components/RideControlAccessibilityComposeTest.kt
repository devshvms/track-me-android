package `in`.shvms.trackme.ui.home.components

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import `in`.shvms.trackme.ui.localization.AppStrings
import `in`.shvms.trackme.ui.localization.getAppStrings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    application = Application::class,
    sdk = [34],
    qualifiers = "w411dp-h891dp"
)
class RideControlAccessibilityComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pauseSemanticsActionIsOperableExactlyOnce() {
        var pauseToggles = 0
        val strings = AppStrings()

        composeRule.setContent {
            UnifiedPauseStopPill(
                isPaused = false,
                strings = strings,
                onPauseToggle = { pauseToggles++ },
                onStopRide = {}
            )
        }
        composeRule.mainClock.autoAdvance = false

        val pauseNode = composeRule.onNodeWithContentDescription(strings.pauseTracking)
        pauseNode.fetchSemanticsNode()
        pauseNode.performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle { assertEquals(1, pauseToggles) }
    }

    @Test
    fun stopSemanticsActionRequestsStopExactlyOnce() {
        var stopRequests = 0
        val strings = AppStrings()
        composeRule.setContent {
            UnifiedPauseStopPill(
                isPaused = false,
                strings = strings,
                onPauseToggle = {},
                onStopRide = { stopRequests++ }
            )
        }
        composeRule.mainClock.autoAdvance = false

        composeRule.onNodeWithContentDescription(strings.stopTracking)
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithContentDescription(strings.stopTracking)
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(strings.stopTracking)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, strings.rideStopped))
        composeRule.runOnIdle { assertEquals(1, stopRequests) }
    }

    @Test
    fun stopSemanticsUsesLocalizedLabel() {
        val strings = getAppStrings("es")

        composeRule.setContent {
            UnifiedPauseStopPill(
                isPaused = false,
                strings = strings,
                onPauseToggle = {},
                onStopRide = {}
            )
        }
        composeRule.mainClock.autoAdvance = false

        composeRule.onNodeWithContentDescription(strings.stopTracking).fetchSemanticsNode()
    }
}
