package `in`.shvms.trackme.analytics

import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RecapTelemetryTest {

    @Test
    fun recapTelemetry_methodsCanBeInvokedSafelyWhenTelemetryDisabled() {
        AnalyticsManager.updateLocalConsent(false)

        // None of these should throw or fail when telemetry is off
        AnalyticsManager.trackRecapSent("2026-W45", 5, 42.8)
        AnalyticsManager.trackRecapOpened("notification", "2026-W45", 5, 42.8)
        AnalyticsManager.trackRecapOpened("in_app", "2026-W45", 5, 42.8)
        AnalyticsManager.trackRecapShared("2026-W45", 5, 42.8)
    }
}
