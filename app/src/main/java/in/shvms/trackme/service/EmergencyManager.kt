package `in`.shvms.trackme.service

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * TG-A03 (1.6.4): the SOS trigger surface is gone, but this class stays — TrackingService
 * depends on it for per-ride celebration suppression (HAZARD-2). It now only *reads* the
 * persisted emergency state; nothing in the app can set it any more, and SosStateCleanup
 * clears any state left behind by a pre-1.6.4 install (HAZARD-1).
 */
class EmergencyManager(
    private val trackingPreferences: SharedPreferences,
) {
    companion object {
        internal const val EMERGENCY_ACTIVE_KEY = "emergency_active"
        internal const val EMERGENCY_STARTED_AT_KEY = "emergency_started_at"
        internal const val EMERGENCY_TRIGGERED_FOR_RIDE_KEY = "emergency_triggered_for_ride"
    }

    private val _isEmergencyActive = MutableStateFlow(
        trackingPreferences.getBoolean(EMERGENCY_ACTIVE_KEY, false)
    )
    val isEmergencyActive: StateFlow<Boolean> = _isEmergencyActive.asStateFlow()

    /**
     * Whether the current ride has entered the emergency flow. This is deliberately separate
     * from [isEmergencyActive]: a user can resolve SOS before stopping the ride, but the
     * celebratory post-ride surfaces must remain suppressed for that ride.
     */
    private var emergencyTriggeredForRide = trackingPreferences
        .getBoolean(EMERGENCY_TRIGGERED_FOR_RIDE_KEY, false)

    @Synchronized
    fun beginRideSession() {
        // Carry an already-active SOS across a ride split; otherwise the new segment could
        // incorrectly earn a celebratory reveal while the emergency flow is still running.
        emergencyTriggeredForRide = _isEmergencyActive.value
        persistSuppression()
    }

    /** Consume the per-ride suppression bit exactly once at finalization. */
    @Synchronized
    fun consumeRideSuppression(): Boolean {
        val wasTriggered = trackingPreferences
            .getBoolean(EMERGENCY_TRIGGERED_FOR_RIDE_KEY, false)
        emergencyTriggeredForRide = false
        trackingPreferences.edit().remove(EMERGENCY_TRIGGERED_FOR_RIDE_KEY).apply()
        return wasTriggered
    }

    private fun persistSuppression() {
        trackingPreferences
            .edit()
            .putBoolean(EMERGENCY_TRIGGERED_FOR_RIDE_KEY, emergencyTriggeredForRide)
            .apply()
    }
}
