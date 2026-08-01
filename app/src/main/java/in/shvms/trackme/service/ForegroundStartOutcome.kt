package `in`.shvms.trackme.service

/**
 * The user-visible consequence of a foreground-service start failure.
 *
 * This type deliberately has no Android API 31 references.  Tracking can be restored on older
 * devices and the same classifier is exercised by the host-side unit tests without Robolectric.
 */
enum class ForegroundStartFailure {
    PERMISSION_REVOKED,
    BACKGROUND_START_BLOCKED,
    OTHER;

    val shouldShowLocationPermissionRevokedNotice: Boolean
        get() = this == PERMISSION_REVOKED
}

/** Backwards-friendly name for callers that model the classifier as an outcome. */
typealias ForegroundStartOutcome = ForegroundStartFailure

/** Pure policy for classifying failures raised by startForeground/startForegroundService. */
object ForegroundStartPolicy {
    private const val FGS_NOT_ALLOWED_EXCEPTION =
        "android.app.ForegroundServiceStartNotAllowedException"

    fun classify(throwable: Throwable, sdkInt: Int): ForegroundStartFailure = when {
        throwable is SecurityException -> ForegroundStartOutcome.PERMISSION_REVOKED
        sdkInt >= 31 && (
            throwable.javaClass.name == FGS_NOT_ALLOWED_EXCEPTION ||
                throwable.javaClass.simpleName == "ForegroundServiceStartNotAllowedException"
            ) ->
            ForegroundStartOutcome.BACKGROUND_START_BLOCKED
        else -> ForegroundStartOutcome.OTHER
    }

    fun shouldAbandonSession(failure: ForegroundStartFailure): Boolean = true

    fun shouldShowRevokedNotice(failure: ForegroundStartFailure): Boolean =
        failure == ForegroundStartFailure.PERMISSION_REVOKED
}
