package `in`.shvms.trackme.settings

import android.content.SharedPreferences

/** Local-only debug access and the complete set of preferences it is allowed to override. */
internal object DebugSettings {
    const val MODE_ENABLED_KEY = "debug_mode_enabled"
    const val AUTO_PAUSE_KEY = "intelligent_auto_pause"
    const val DISABLE_POST_PROCESSING_KEY = "disable_gps_post_processing"

    fun isEnabled(preferences: SharedPreferences): Boolean =
        preferences.getBoolean(MODE_ENABLED_KEY, false)

    fun enable(preferences: SharedPreferences) {
        preferences.edit().putBoolean(MODE_ENABLED_KEY, true).apply()
    }

    /**
     * Locks the page and restores only the settings owned by debug mode. Unrelated customer
     * preferences must never be changed by this operation.
     */
    fun disableAndReset(preferences: SharedPreferences) {
        preferences.edit()
            .putBoolean(MODE_ENABLED_KEY, false)
            .putBoolean(AUTO_PAUSE_KEY, true)
            .putBoolean(DISABLE_POST_PROCESSING_KEY, false)
            .apply()
    }
}

/** Pure, monotonic-time five-tap gate shared by UI and unit tests. */
internal class ConsecutiveTapUnlock(
    private val requiredTaps: Int = 5,
    private val maximumGapMillis: Long = 2_000L,
) {
    init {
        require(requiredTaps > 0)
        require(maximumGapMillis >= 0L)
    }

    var tapCount: Int = 0
        private set
    private var lastTapElapsedMillis: Long? = null

    fun registerTap(elapsedMillis: Long): Boolean {
        val lastTap = lastTapElapsedMillis
        tapCount = if (
            lastTap == null ||
            elapsedMillis < lastTap ||
            elapsedMillis - lastTap > maximumGapMillis
        ) {
            1
        } else {
            tapCount + 1
        }
        lastTapElapsedMillis = elapsedMillis

        if (tapCount < requiredTaps) return false
        reset()
        return true
    }

    fun reset() {
        tapCount = 0
        lastTapElapsedMillis = null
    }
}
