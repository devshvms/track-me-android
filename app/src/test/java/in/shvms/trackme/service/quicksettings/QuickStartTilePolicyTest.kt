package `in`.shvms.trackme.service.quicksettings

import android.service.quicksettings.Tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickStartTilePolicyTest {

    @Test
    fun `tracking is active only when running and has active ride id`() {
        assertTrue(QuickStartTilePolicy.isTrackingActive(isRunning = true, activeRideId = 123L))
        assertFalse(QuickStartTilePolicy.isTrackingActive(isRunning = false, activeRideId = 123L))
        assertFalse(QuickStartTilePolicy.isTrackingActive(isRunning = true, activeRideId = null))
        assertFalse(QuickStartTilePolicy.isTrackingActive(isRunning = false, activeRideId = null))
    }

    @Test
    fun `tile states reflect active vs inactive tracking`() {
        assertEquals(Tile.STATE_ACTIVE, QuickStartTilePolicy.tileState(isTracking = true))
        assertEquals(Tile.STATE_INACTIVE, QuickStartTilePolicy.tileState(isTracking = false))
        assertEquals("Recording Ride", QuickStartTilePolicy.tileLabel(isTracking = true))
        assertEquals("Start Ride", QuickStartTilePolicy.tileLabel(isTracking = false))
    }
}
