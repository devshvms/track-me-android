package `in`.shvms.trackme.ui.home

import `in`.shvms.trackme.analytics.RideStartAbortMethod
import `in`.shvms.trackme.service.TrackingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideStartUndoTest {
    @Test
    fun `undo is visible only between grace threshold and window limit while distance is below limit`() {
        assertFalse("Must not show undo during 1.5s grace period to prevent accidental double-tap aborts", shouldShowRideStartUndo(0L, 0f))
        assertFalse("Must not show undo at 1499ms", shouldShowRideStartUndo(1_499L, 0f))
        assertTrue("Shows undo starting at 1500ms", shouldShowRideStartUndo(1_500L, 0f))
        assertTrue("Shows undo at 9999ms", shouldShowRideStartUndo(9_999L, 9.99f))

        assertFalse("Hides undo at window limit 10000ms", shouldShowRideStartUndo(RIDE_START_UNDO_WINDOW_MILLIS, 0f))
        assertFalse(
            "Hides undo when distance exceeds junk distance",
            shouldShowRideStartUndo(
                elapsedDurationMillis = 5_000L,
                distanceMeters = TrackingService.JUNK_RIDE_DISTANCE_METERS.toFloat()
            )
        )
    }

    @Test
    fun `abort telemetry contract matches iOS parity values`() {
        assertEquals("pre_commit", RideStartAbortMethod.PRE_COMMIT.analyticsValue)
        assertEquals("post_commit_undo", RideStartAbortMethod.POST_COMMIT_UNDO.analyticsValue)
        assertEquals("short_indoor_test", RideStartAbortMethod.SHORT_INDOOR_TEST.analyticsValue)
    }
}
