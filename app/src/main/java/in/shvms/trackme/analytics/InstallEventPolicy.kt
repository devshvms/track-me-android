package `in`.shvms.trackme.analytics

/**
 * TASK-355. Owns the once-per-install `Application Installed` event on Android.
 *
 * **Why this exists.** Until 1.8.11 the PostHog SDK emitted `Application Installed` itself
 * (`captureApplicationLifecycleEvents`). TASK-330 then set `optOut = true` *before*
 * `PostHogAndroid.setup()` so nothing could leave the device ahead of consent. On a fresh install
 * consent is always still false at that moment, so the SDK drops the install event — and still marks
 * the install as handled, so it never retries. PostHog confirms it: Android 1.8.11 recorded 191
 * installs, 1.9.0 recorded none, while `Application Opened`/`Updated` kept flowing.
 *
 * **Contract.** A fresh install (never updated) is [InstallEventState.PENDING] until telemetry is
 * enabled, then exactly one event is emitted and the state becomes [InstallEventState.SENT]. An app
 * that was *updated into* this build is [InstallEventState.NOT_APPLICABLE]: it is not an install and
 * counting it would inflate the acquisition number. No consent is bypassed — the event is only ever
 * emitted while telemetry is enabled, so it can only add what the user already allowed.
 *
 * Pure and Android-free on purpose so every branch is unit-testable without an emulator.
 */
enum class InstallEventState {
    PENDING,
    SENT,
    NOT_APPLICABLE;

    companion object {
        fun fromStored(value: String?): InstallEventState? =
            entries.firstOrNull { it.name == value }
    }
}

object InstallEventPolicy {
    /** firstInstallTime and lastUpdateTime differ by a few ms on a fresh install; tolerate a minute. */
    const val FRESH_INSTALL_TOLERANCE_MS: Long = 60_000L

    /** Decides the state for a build seeing no stored state yet. A stored state always wins. */
    fun resolveState(
        stored: InstallEventState?,
        firstInstallTimeMs: Long,
        lastUpdateTimeMs: Long,
    ): InstallEventState {
        if (stored != null) return stored
        val isFreshInstall = lastUpdateTimeMs - firstInstallTimeMs <= FRESH_INSTALL_TOLERANCE_MS
        return if (isFreshInstall) InstallEventState.PENDING else InstallEventState.NOT_APPLICABLE
    }

    fun shouldEmit(state: InstallEventState, telemetryEnabled: Boolean): Boolean =
        telemetryEnabled && state == InstallEventState.PENDING
}
