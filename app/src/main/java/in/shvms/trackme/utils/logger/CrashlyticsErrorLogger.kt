package `in`.shvms.trackme.utils.logger

import com.google.firebase.crashlytics.FirebaseCrashlytics
import `in`.shvms.trackme.analytics.TelemetryEnvironment

class CrashlyticsErrorLogger : ErrorLogger {
    private val crashlytics: FirebaseCrashlytics
        get() = FirebaseCrashlytics.getInstance()

    override fun init() {
        // Crashlytics automatically captures fatal exceptions, but we can configure defaults here.
        //
        // TASK-250: collection follows the same environment gate as analytics. A debug build or an
        // emulator crashing is a crash someone is already looking at in a debugger; sending it
        // makes the production crash-free-users rate a number about developers rather than riders,
        // and a deliberately-crashed test run can bury a real regression under its own noise.
        crashlytics.setCrashlyticsCollectionEnabled(TelemetryEnvironment.allowsDelivery)
    }

    override fun setUserId(userId: String?) {
        if (userId != null) {
            crashlytics.setUserId(userId)
        } else {
            crashlytics.setUserId("")
        }
    }

    override fun setCustomKey(key: String, value: String) {
        crashlytics.setCustomKey(key, value)
    }

    override fun recordException(throwable: Throwable) {
        // Dropped here rather than at the 22 call sites: a caller reporting a failure should not
        // have to know which of its exceptions are really coroutine bookkeeping. See isReportable.
        if (!isReportable(throwable)) return
        crashlytics.recordException(throwable)
    }

    override fun log(message: String) {
        crashlytics.log(message)
    }
}
