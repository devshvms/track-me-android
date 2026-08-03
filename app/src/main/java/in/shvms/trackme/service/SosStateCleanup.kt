package `in`.shvms.trackme.service

import android.content.SharedPreferences

/**
 * TG-A05 / HAZARD-1 (1.6.4): the SOS dispatch machinery is gone, but pre-1.6.4 installs
 * persisted the SOS state synchronously and kill-safely. A user who upgrades with an
 * active SOS would otherwise sit in a permanent "emergency active" state with no dispatch
 * behind it and no UI left to resolve it.
 *
 * This runs exactly once, guarded by [CLEARED_FLAG_KEY], and must be invoked early in
 * `Application.onCreate` — before [EmergencyManager] is constructed or any UI reads the
 * state. The clear uses `commit()` deliberately, matching the write discipline of the
 * state it removes: a process kill between the clear and an async flush must not
 * resurrect the stranded state.
 */
object SosStateCleanup {
    internal const val CLEARED_FLAG_KEY = "sos_state_cleared_v164"

    /**
     * @return true when this call performed the clear; false when it had already run.
     */
    fun clearOnce(trackingPreferences: SharedPreferences): Boolean {
        if (trackingPreferences.getBoolean(CLEARED_FLAG_KEY, false)) return false
        trackingPreferences.edit()
            .remove(EmergencyManager.EMERGENCY_ACTIVE_KEY)
            .remove(EmergencyManager.EMERGENCY_STARTED_AT_KEY)
            .remove(EmergencyManager.EMERGENCY_TRIGGERED_FOR_RIDE_KEY)
            .putBoolean(CLEARED_FLAG_KEY, true)
            .commit()
        return true
    }
}
