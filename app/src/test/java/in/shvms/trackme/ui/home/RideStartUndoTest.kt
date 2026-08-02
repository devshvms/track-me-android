package `in`.shvms.trackme.ui.home

import `in`.shvms.trackme.analytics.RideStartAbortMethod
import `in`.shvms.trackme.service.TrackingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideStartUndoTest {
    @Test
    fun `undo is visible only while both time and distance are below limits`() {
        assertTrue(shouldShowRideStartUndo(9_999L, 9.99f))

        assertFalse(shouldShowRideStartUndo(RIDE_START_UNDO_WINDOW_MILLIS, 0f))
        assertFalse(
            shouldShowRideStartUndo(
                elapsedDurationMillis = 0L,
                distanceMeters = TrackingService.JUNK_RIDE_DISTANCE_METERS.toFloat()
            )
        )
        assertFalse(
            shouldShowRideStartUndo(
                elapsedDurationMillis = RIDE_START_UNDO_WINDOW_MILLIS,
                distanceMeters = TrackingService.JUNK_RIDE_DISTANCE_METERS.toFloat()
            )
        )
    }

    @Test
    fun `abort telemetry contract matches iOS parity values`() {
        assertEquals("pre_commit", RideStartAbortMethod.PRE_COMMIT.analyticsValue)
        assertEquals("post_commit_undo", RideStartAbortMethod.POST_COMMIT_UNDO.analyticsValue)
    }
}
