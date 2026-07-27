package `in`.shvms.trackme.service

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class EmergencyManager(
    private val trackingPreferences: SharedPreferences? = null,
) {
    companion object {
        private const val EMERGENCY_TRIGGERED_FOR_RIDE_KEY = "emergency_triggered_for_ride"
    }

    private val _isEmergencyActive = MutableStateFlow(false)
    val isEmergencyActive: StateFlow<Boolean> = _isEmergencyActive.asStateFlow()

    /**
     * Whether the current ride has entered the emergency flow. This is deliberately separate
     * from [isEmergencyActive]: a user can resolve SOS before stopping the ride, but the
     * celebratory post-ride surfaces must remain suppressed for that ride.
     */
    private var emergencyTriggeredForRide = trackingPreferences
        ?.getBoolean(EMERGENCY_TRIGGERED_FOR_RIDE_KEY, false)
        ?: false

    @Synchronized
    fun beginRideSession() {
        // Carry an already-active SOS across a ride split; otherwise the new segment could
        // incorrectly earn a celebratory reveal while the emergency flow is still running.
        emergencyTriggeredForRide = if (trackingPreferences != null) {
            trackingPreferences.getBoolean(EMERGENCY_TRIGGERED_FOR_RIDE_KEY, false) ||
                _isEmergencyActive.value
        } else {
            _isEmergencyActive.value
        }
        persistSuppression()
    }

    @Synchronized
    fun triggerEmergency() {
        emergencyTriggeredForRide = true
        _isEmergencyActive.value = true
        persistSuppression()
    }

    @Synchronized
    fun stopEmergency() {
        _isEmergencyActive.value = false
    }

    /** Consume the per-ride suppression bit exactly once at finalization. */
    @Synchronized
    fun consumeRideSuppression(): Boolean {
        val wasTriggered = trackingPreferences
            ?.getBoolean(EMERGENCY_TRIGGERED_FOR_RIDE_KEY, false)
            ?: emergencyTriggeredForRide
        emergencyTriggeredForRide = false
        trackingPreferences?.edit()?.remove(EMERGENCY_TRIGGERED_FOR_RIDE_KEY)?.apply()
        return wasTriggered
    }

    private fun persistSuppression() {
        trackingPreferences
            ?.edit()
            ?.putBoolean(EMERGENCY_TRIGGERED_FOR_RIDE_KEY, emergencyTriggeredForRide)
            ?.apply()
    }
}
