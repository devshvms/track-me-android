package `in`.shvms.trackme.utils.logger

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
     */
    fun recordException(throwable: Throwable)

    /**
     * Logs a simple message to be attached to crash reports.
     */
    fun log(message: String)
}
