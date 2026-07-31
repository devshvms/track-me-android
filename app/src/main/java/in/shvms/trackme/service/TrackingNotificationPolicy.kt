package `in`.shvms.trackme.service

import java.util.Locale

/** Snapshot of the values used to decide whether the foreground notification needs a refresh. */
internal data class TrackingNotificationThrottle(
    val lastNotifyElapsedMs: Long = Long.MIN_VALUE,
    val lastNotifyDistanceMeters: Float = 0f,
    val lastNotifyState: TrackingState? = null
)

/**
 * Mirrors the iOS Live Activity update cadence: state changes are immediate, movement of 25 m
 * bypasses the timer, and otherwise the notification is refreshed at most every 15 seconds.
 */
internal fun shouldUpdateTrackingNotification(
    nowElapsedMs: Long,
    distanceMeters: Float,
    state: TrackingState,
    previous: TrackingNotificationThrottle
): Boolean {
    if (previous.lastNotifyState == null || state != previous.lastNotifyState) return true
    if (nowElapsedMs - previous.lastNotifyElapsedMs >= TrackingService.NOTIFICATION_UPDATE_INTERVAL_MS) {
        return true
    }
    return distanceMeters - previous.lastNotifyDistanceMeters >=
        TrackingService.NOTIFICATION_DISTANCE_DELTA_METERS
}

/** Formats the active ride duration as the stable `HH:MM:SS` string used by the live HUD. */
internal fun formatTrackingNotificationDurationValue(durationMillis: Long): String {
    val totalSeconds = durationMillis.coerceAtLeast(0L) / 1000L
    return String.format(
        Locale.getDefault(),
        "%02d:%02d:%02d",
        totalSeconds / 3600L,
        (totalSeconds % 3600L) / 60L,
        totalSeconds % 60L
    )
}
