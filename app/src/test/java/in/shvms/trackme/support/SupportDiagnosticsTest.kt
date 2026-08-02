package `in`.shvms.trackme.support

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportDiagnosticsTest {
    @Test
    fun rendersPermissionAndDeviceContextWithoutLocationOrRideData() {
        val rendered = SupportDiagnostics.render(
            SupportDiagnosticsInput(
                appVersion = "1.6.1 (42)",
                androidVersion = "15 (API 35)",
                device = "Acme Trail 1",
                appLanguage = "en",
                deviceLocale = "en-US",
                units = "metric",
                installSource = "com.android.vending",
                locationPermission = "precise, background allowed",
                notificationPermission = "granted",
                batteryOptimization = "granted",
                signedIn = false
            )
        )

        assertTrue(rendered.contains("App version: 1.6.1 (42)"))
        assertTrue(rendered.contains("Location permission: precise, background allowed"))
        assertTrue(rendered.contains("Notification permission: granted"))
        assertFalse(Regex("-?\\d{1,3}\\.\\d{4,}").containsMatchIn(rendered))
        assertFalse(Regex("(?i)\\b(lat|lon|ride)\\b").containsMatchIn(rendered))
    }
}
