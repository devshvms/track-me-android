package `in`.shvms.trackme.analytics

/**
 * TASK-356. `ride_recovered` makes the ghost-ride gap measurable.
 *
 * Orphaned-ride recovery already exists (`OrphanedRideRecoveryManager`, called from `TrackMeApp`),
 * but it emitted nothing: PostHog could show 276 `ride_started` against 100 `ride_completed` and
 * not say how many of the missing rides were quietly rescued versus truly lost. This is the
 * missing counter, so the next decision about a resume UI rests on data rather than on the
 * assumption that recovery fails.
 *
 * Privacy: coarse only. A raw distance next to a timestamp can be a location fingerprint, so the
 * event carries counts and a distance *bucket*, never metres, titles or ids (same rule as the
 * Home dashboard taxonomy).
 */
object RecoveryTelemetry {
    const val EVENT = "ride_recovered"
    const val GUARD_SHOWN_EVENT = "recovery_battery_guard_shown"
    const val GUARD_OPENED_EVENT = "recovery_battery_settings_opened"
    val GUARD_SURFACES = setOf("snackbar", "notification")

    fun distanceBucket(totalMeters: Double): String = when {
        totalMeters < 1_000.0 -> "lt_1km"
        totalMeters < 5_000.0 -> "1_5km"
        totalMeters < 20_000.0 -> "5_20km"
        else -> "gte_20km"
    }

    fun properties(
        recoveredCount: Int,
        discardedCount: Int,
        totalMeters: Double,
        batteryOptimized: Boolean? = null,
    ): Map<String, Any> = buildMap {
        put("recovered_count", recoveredCount)
        put("discarded_count", discardedCount)
        put("distance_bucket", distanceBucket(totalMeters))
        if (batteryOptimized != null) {
            put("battery_optimized", batteryOptimized)
        }
    }

    fun guardProperties(surface: String): Map<String, Any> {
        require(surface in GUARD_SURFACES) { "Unsupported recovery battery guard surface: $surface" }
        return mapOf("surface" to surface)
    }

    /** Nothing to report unless recovery actually changed something. */
    fun shouldReport(recoveredCount: Int, discardedCount: Int): Boolean =
        recoveredCount > 0 || discardedCount > 0
}
