package `in`.shvms.trackme.service

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class EmergencyManager(
    private val trackingPreferences: SharedPreferences,
) {
    companion object {
        private const val EMERGENCY_ACTIVE_KEY = "emergency_active"
        private const val EMERGENCY_STARTED_AT_KEY = "emergency_started_at"
        private const val EMERGENCY_TRIGGERED_FOR_RIDE_KEY = "emergency_triggered_for_ride"
    }

    private val _isEmergencyActive = MutableStateFlow(
        trackingPreferences.getBoolean(EMERGENCY_ACTIVE_KEY, false)
    )
    val isEmergencyActive: StateFlow<Boolean> = _isEmergencyActive.asStateFlow()

    private val _emergencyStartedAtMillis = MutableStateFlow(
        trackingPreferences.getLong(EMERGENCY_STARTED_AT_KEY, 0L).takeIf { it > 0L }
    )
    /** Epoch milliseconds at which the current SOS began, if one is active or being resolved. */
    val emergencyStartedAtMillis: StateFlow<Long?> = _emergencyStartedAtMillis.asStateFlow()

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

    @Synchronized
    fun triggerEmergency() {
        emergencyTriggeredForRide = true
        _isEmergencyActive.value = true
        if (_emergencyStartedAtMillis.value == null) {
            _emergencyStartedAtMillis.value = System.currentTimeMillis()
        }
        // SOS is safety-critical: synchronously persist the active bit and start timestamp
        // before returning so an immediate process kill cannot lose the recovery state.
        persistEmergencyState(commit = true)
        persistSuppression()
    }

    @Synchronized
    fun stopEmergency() {
        _isEmergencyActive.value = false
        _emergencyStartedAtMillis.value = null
        // A user cancellation is safety-critical too: losing this write after STOP can resurrect
        // the broadcast on the next process start, overriding the user's explicit resolution.
        persistEmergencyState(commit = true)
    }

    /**
     * Migrates an active emergency created before [EMERGENCY_STARTED_AT_KEY] existed. Such an
     * emergency has no historical timestamp, so start its bounded broadcast window now rather
     * than allowing a null clock to reset the worker on every process recreation.
     */
    @Synchronized
    fun ensureEmergencyStartedAt(): Long? {
        if (!_isEmergencyActive.value) return null
        val existing = _emergencyStartedAtMillis.value
        if (existing != null) return existing
        val startedAt = System.currentTimeMillis()
        _emergencyStartedAtMillis.value = startedAt
        persistEmergencyState(commit = true)
        return startedAt
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

    private fun persistEmergencyState(commit: Boolean) {
        val editor = trackingPreferences
            .edit()
            .putBoolean(EMERGENCY_ACTIVE_KEY, _isEmergencyActive.value)
        val startedAt = _emergencyStartedAtMillis.value
        if (startedAt != null) {
            editor.putLong(EMERGENCY_STARTED_AT_KEY, startedAt)
        } else {
            editor.remove(EMERGENCY_STARTED_AT_KEY)
        }
        if (commit) editor.commit() else editor.apply()
    }
}
