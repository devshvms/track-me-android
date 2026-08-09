package `in`.shvms.trackme.utils.logger

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
 * Whether [throwable] describes a real failure, or merely a coroutine that was cancelled.
 *
 * Crashlytics was collecting `kotlinx.coroutines.JobCancellationException` as a non-fatal. It is
 * not a fault: it is how structured concurrency *reports success at stopping* — a scope tied to a
 * screen cancels its children when the user navigates away, and every in-flight job throws it on
 * the way out. Normal use therefore generates a steady stream of them.
 *
 * The cost is not the storage, it is that the noise crowds out signal. A non-fatal that fires on
 * every navigation trains everyone to ignore the non-fatal list, which is where the *next* real
 * bug will appear.
 *
 * Only the throwable itself is examined, not its cause chain. A genuine failure that happens to
 * carry a cancellation as its cause is still a genuine failure, and dropping it because of what
 * is underneath would hide exactly the kind of bug this list exists for.
 */
fun isReportable(throwable: Throwable): Boolean = throwable !is CancellationException
