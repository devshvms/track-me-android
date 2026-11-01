package `in`.shvms.trackme.ui.home.components

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.data.remote.LiveShareState
import `in`.shvms.trackme.service.TrackingState
import `in`.shvms.trackme.ui.localization.AppStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TASK-350 (LOCK-01): TouchLockGuardTest.
 * Verifies that the HUD Touch Lock pill transitions to locked state
 * and shields tracking controls from accidental pocket taps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    application = Application::class,
    sdk = [34],
    qualifiers = "w411dp-h891dp"
)
class TouchLockGuardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun touchLockPillLocksControlsOnTap() {
        val strings = AppStrings()
        var pauseToggles = 0
        var stopRequests = 0

        composeRule.setContent {
            ActiveRideHudPanel(
                trackingState = TrackingState.TRACKING,
                distanceText = "2.4 km",
                durationText = "12:30",
                elapsedDurationText = "12:30",
                speedText = "45 km/h",
                paceText = "5:12 /km",
                selectedPersona = RidePersona.CAR_DRIVE,
                isAutoPaused = false,
                timeSinceLastGps = 500L,
                liveShareState = LiveShareState(),
                isAuthenticated = true,
                liveShareAuthRequired = "",
                onPauseToggle = { pauseToggles++ },
                onStopRide = { stopRequests++ },
                onStartShare = {},
                onStopShare = {},
                onSendShare = {},
                onCopyShare = {}
            )
        }

        // Initially unlocked: "Lock" pill is displayed
        composeRule.onNodeWithText(strings.hudTouchLockPill).assertExists()

        // Tap Lock pill
        composeRule.onNodeWithText(strings.hudTouchLockPill).performClick()
        composeRule.waitForIdle()

        // Now locked: "Locked" pill is displayed, and unlock hint overlay is displayed
        composeRule.onNodeWithText(strings.hudTouchLockedPill).assertExists()
        composeRule.onNodeWithText(strings.hudTouchUnlockHint).assertExists()

        // Verify that accidental single tap on the HUD area does not trigger pause or stop
        composeRule.onNodeWithText(strings.hudTouchUnlockHint).performClick()
        composeRule.waitForIdle()

        assertEquals(0, pauseToggles)
        assertEquals(0, stopRequests)
    }

    @Test
    fun localizedStringsForTouchLockAreNonEmpty() {
        val strings = AppStrings()
        assertTrue(strings.hudTouchLockPill.isNotBlank())
        assertTrue(strings.hudTouchLockedPill.isNotBlank())
        assertTrue(strings.hudTouchUnlockHint.isNotBlank())
        assertTrue(strings.hudTouchUnlocking.isNotBlank())
    }
}
