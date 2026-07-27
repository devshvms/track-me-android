package `in`.shvms.trackme.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class EmergencyManager {
    private val _isEmergencyActive = MutableStateFlow(false)
    val isEmergencyActive: StateFlow<Boolean> = _isEmergencyActive.asStateFlow()

    /**
     * Whether the current ride has entered the emergency flow. This is deliberately separate
     * from [isEmergencyActive]: a user can resolve SOS before stopping the ride, but the
     * celebratory post-ride surfaces must remain suppressed for that ride.
     */
    private var emergencyTriggeredForRide = false

    @Synchronized
    fun beginRideSession() {
        // Carry an already-active SOS across a ride split; otherwise the new segment could
        // incorrectly earn a celebratory reveal while the emergency flow is still running.
        emergencyTriggeredForRide = _isEmergencyActive.value
    }

    @Synchronized
    fun triggerEmergency() {
        emergencyTriggeredForRide = true
        _isEmergencyActive.value = true
    }

    @Synchronized
    fun stopEmergency() {
        _isEmergencyActive.value = false
    }

    /** Consume the per-ride suppression bit exactly once at finalization. */
    @Synchronized
    fun consumeRideSuppression(): Boolean {
        val wasTriggered = emergencyTriggeredForRide
        emergencyTriggeredForRide = false
        return wasTriggered
    }
}
