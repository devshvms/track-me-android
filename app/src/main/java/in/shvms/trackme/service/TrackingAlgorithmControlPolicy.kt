package `in`.shvms.trackme.service

/**
 * Resolves the internal auto-pause override without letting a stale preference silently disable
 * supported behavior while Debug Settings is locked.
 *
 * Keeping this pure makes the lock boundary independently testable instead of relying on which
 * Settings composable happens to be visible.
 */
internal object TrackingAlgorithmControlPolicy {
    fun autoPauseEnabled(
        debugModeEnabled: Boolean,
        storedEnabled: Boolean,
    ): Boolean = !debugModeEnabled || storedEnabled
}
