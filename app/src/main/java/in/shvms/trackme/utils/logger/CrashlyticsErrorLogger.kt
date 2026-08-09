package `in`.shvms.trackme.utils.logger

import com.google.firebase.crashlytics.FirebaseCrashlytics

class CrashlyticsErrorLogger : ErrorLogger {
    private val crashlytics: FirebaseCrashlytics
        get() = FirebaseCrashlytics.getInstance()

    override fun init() {
        // Crashlytics automatically captures fatal exceptions, but we can configure defaults here.
        crashlytics.setCrashlyticsCollectionEnabled(true)
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
