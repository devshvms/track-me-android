package `in`.shvms.trackme.service

/**
 * TASK-351 (BATT-01): Adaptive GPS Polling Throttling Policy.
 *
 * When a rider or driver is stationary (e.g. stopped at long traffic lights, lunch stop,
 * or prolonged rest stop > 3 minutes), continuing to poll GPS every 1–2 seconds wastes 35–45%
 * battery on stationary drift and CPU wakeups.
 *
 * This policy governs stepping down location polling from 1–2s to 5–10s when stationary for
 * > 3 minutes (180,000ms), and instantly restoring 1s high-accuracy polling the moment the
 * hardware IMU accelerometer detects physical movement.
 */
object AdaptiveGpsThrottlePolicy {
    /** Minimum duration of confirmed stillness before stepping down GPS polling rate. */
    const val STATIONARY_THROTTLE_THRESHOLD_MS: Long = 180_000L // 3 minutes

    /** Active high-accuracy intervals. */
    const val ACTIVE_INTERVAL_MS: Long = 2000L
    const val ACTIVE_MIN_INTERVAL_MS: Long = 1000L

    /** Throttled stationary intervals. */
    const val THROTTLED_INTERVAL_MS: Long = 10_000L
    const val THROTTLED_MIN_INTERVAL_MS: Long = 5000L

    /**
     * Evaluates whether location polling should be throttled.
     *
     * @param isStationary true if hardware IMU or drift detector flags the device as stationary.
     * @param stillnessDurationMs duration in milliseconds since stillness began.
     * @return true if GPS polling rate should be stepped down.
     */
    fun shouldThrottle(
        isStationary: Boolean,
        stillnessDurationMs: Long,
    ): Boolean {
        return isStationary && stillnessDurationMs >= STATIONARY_THROTTLE_THRESHOLD_MS
    }

    /**
     * Evaluates whether high-accuracy location polling should be restored immediately.
     *
     * @param isCurrentlyThrottled true if GPS polling is currently in throttled state.
     * @param isStationary true if device is still stationary.
     * @return true if high-accuracy polling should be restored immediately.
     */
    fun shouldRestore(
        isCurrentlyThrottled: Boolean,
        isStationary: Boolean,
    ): Boolean {
        return isCurrentlyThrottled && !isStationary
    }
}
