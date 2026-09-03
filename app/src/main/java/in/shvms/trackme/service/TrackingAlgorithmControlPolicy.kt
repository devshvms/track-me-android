package `in`.shvms.trackme.service

/**
 * Resolves internal tracking-algorithm overrides without letting a stale preference silently
 * disable supported behavior while Debug Settings is locked.
 *
 * Keeping this pure makes the lock boundary independently testable instead of relying on which
 * Settings composable happens to be visible.
 */
internal object TrackingAlgorithmControlPolicy {
    fun autoPauseEnabled(
        debugModeEnabled: Boolean,
        storedEnabled: Boolean,
    ): Boolean = !debugModeEnabled || storedEnabled

    fun postProcessingEnabled(
        debugModeEnabled: Boolean,
        storedDisabled: Boolean,
    ): Boolean = !debugModeEnabled || !storedDisabled
}
