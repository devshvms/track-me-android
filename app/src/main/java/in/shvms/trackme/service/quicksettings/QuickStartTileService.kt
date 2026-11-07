package `in`.shvms.trackme.service.quicksettings

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import `in`.shvms.trackme.MainActivity
import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.domain.quickstart.QuickStartRequest
import `in`.shvms.trackme.service.TrackingService

/**
 * Pure policy for Quick Settings tile display states so it is unit-testable without platform mocks.
 */
object QuickStartTilePolicy {
    fun isTrackingActive(isRunning: Boolean, activeRideId: Long?): Boolean =
        isRunning && activeRideId != null

    fun tileState(isTracking: Boolean): Int =
        if (isTracking) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE

    fun tileLabel(isTracking: Boolean): String =
        if (isTracking) "Recording Ride" else "Start Ride"
}

/**
 * TASK-357. Quick Settings tile allowing one-tap ride start from system shade.
 * Starts MainActivity with QUICK_START action so location/notification permissions are honored.
 */
class QuickStartTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val isTracking = QuickStartTilePolicy.isTrackingActive(
            isRunning = TrackingService.isRunning,
            activeRideId = TrackingService.activeRideId
        )
        tile.state = QuickStartTilePolicy.tileState(isTracking)
        tile.label = QuickStartTilePolicy.tileLabel(isTracking)
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            action = QuickStartRequest.ACTION
            putExtra(QuickStartRequest.EXTRA_PERSONA, RidePersona.AUTO.name)
            putExtra(QuickStartRequest.EXTRA_SOURCE, QuickStartRequest.Source.TILE.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
