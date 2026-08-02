package `in`.shvms.trackme.ui.home.components

import `in`.shvms.trackme.ui.localization.getAppStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RideControlAccessibilityTest {
    @Test
    fun `pause control announces the action and state for each ride state`() {
        val strings = getAppStrings("en")

        assertEquals(strings.pauseTracking, RideControlAccessibility.pauseToggleContentDescription(false, strings))
        assertEquals(strings.resumeTracking, RideControlAccessibility.pauseToggleContentDescription(true, strings))
        assertEquals(strings.statusRecording, RideControlAccessibility.pauseToggleStateDescription(false, strings))
        assertEquals(strings.statusPaused, RideControlAccessibility.pauseToggleStateDescription(true, strings))
        assertNotEquals(
            RideControlAccessibility.pauseToggleContentDescription(false, strings),
            RideControlAccessibility.pauseToggleContentDescription(true, strings)
        )
        assertNotEquals(
            RideControlAccessibility.pauseToggleStateDescription(false, strings),
            RideControlAccessibility.pauseToggleStateDescription(true, strings)
        )
    }

    @Test
    fun `stop control announces progress and terminal state`() {
        val strings = getAppStrings("en")

        assertEquals(strings.stopTracking, RideControlAccessibility.stopContentDescription(strings))
        assertEquals(strings.rideInProgress, RideControlAccessibility.stopStateDescription(false, strings))
        assertEquals(strings.rideStopped, RideControlAccessibility.stopStateDescription(true, strings))
        assertNotEquals(
            RideControlAccessibility.stopStateDescription(false, strings),
            RideControlAccessibility.stopStateDescription(true, strings)
        )
    }
}
