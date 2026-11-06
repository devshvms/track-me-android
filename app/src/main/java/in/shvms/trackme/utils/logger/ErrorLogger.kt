package `in`.shvms.trackme.utils.logger

import com.google.firebase.firestore.FirebaseFirestoreException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Modular interface for tracking exceptions.
 * This allows replacing the underlying error logging mechanism (e.g. Crashlytics) 
 * easily without changing the rest of the application.
 */
interface ErrorLogger {
    /**
     * Initializes the logger (e.g., setting up global exception handlers).
     */
    fun init()

    /**
     * Sets the user ID for future error logs to help identify who experienced the crash.
     */
    fun setUserId(userId: String?)

    /**
     * Logs a custom key-value pair to be attached to crash reports.
     */
    fun setCustomKey(key: String, value: String)

    /**
     * Logs a non-fatal exception.
     *
     * Implementations must drop anything [isReportable] rejects, so a caller never has to think
     * about it — there are 22 call sites and they are spread across services, view models and UI.
     */
    fun recordException(throwable: Throwable)

    /**
     * Logs a simple message to be attached to crash reports.
     */
    fun log(message: String)
}

/**
 * Whether [throwable] describes a real failure, or merely a coroutine that was cancelled or a
 * transient/auth-boundary Firestore rejection (`PERMISSION_DENIED`, `UNAUTHENTICATED`,
 * `UNAVAILABLE`, `DEADLINE_EXCEEDED`, `CANCELLED`).
 *
 * Crashlytics was collecting `kotlinx.coroutines.JobCancellationException` and transient Firestore
 * watch-stream rejections (`PERMISSION_DENIED` on sign-out race or unauthenticated watch target,
 * `UNAVAILABLE` offline) as non-fatals. Neither is a fault in app code, and logging them trains
 * everyone to ignore the non-fatal list.
 *
 * Only the throwable itself is examined, not its cause chain. A genuine failure that happens to
 * carry a cancellation as its cause is still a genuine failure, and dropping it because of what
 * is underneath would hide exactly the kind of bug this list exists for.
 */
fun isReportable(throwable: Throwable): Boolean {
    if (throwable is CancellationException) return false
    if (throwable is FirebaseFirestoreException) {
        return isReportableFirestoreCode(throwable.code)
    }
    return true
}

internal fun isReportableFirestoreCode(code: FirebaseFirestoreException.Code): Boolean =
    when (code) {
        FirebaseFirestoreException.Code.PERMISSION_DENIED,
        FirebaseFirestoreException.Code.UNAUTHENTICATED,
        FirebaseFirestoreException.Code.UNAVAILABLE,
        FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
        FirebaseFirestoreException.Code.CANCELLED -> false
        else -> true
    }
