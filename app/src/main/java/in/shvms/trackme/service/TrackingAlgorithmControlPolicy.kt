package `in`.shvms.trackme.service

/**
 * Resolves internal tracking-algorithm overrides without letting a stale customer preference
 * silently disable supported production behavior.
 *
 * The preference keys are retained for debug upgrade/reinstall workflows, but release builds always
 * use the production defaults. Keeping this pure makes the release/debug boundary independently
 * testable instead of relying on which Settings composable happens to be visible.
 */
internal object TrackingAlgorithmControlPolicy {
    fun autoPauseEnabled(
        isDebugBuild: Boolean,
        storedEnabled: Boolean,
    ): Boolean = !isDebugBuild || storedEnabled

    fun postProcessingEnabled(
        isDebugBuild: Boolean,
        storedDisabled: Boolean,
    ): Boolean = !isDebugBuild || !storedDisabled
}
