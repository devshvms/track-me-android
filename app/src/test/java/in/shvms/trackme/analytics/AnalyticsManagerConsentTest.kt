package `in`.shvms.trackme.analytics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsManagerConsentTest {
    @Test
    fun freshInstall_withoutLocalConsent_isDisabled() {
        assertFalse(TelemetryConsentState(localConsent = false, remoteAllowed = true).isEnabled)
    }

    @Test
    fun localConsent_onWithRemoteAllowed_isEnabled() {
        assertTrue(TelemetryConsentState(localConsent = true, remoteAllowed = true).isEnabled)
    }

    @Test
    fun remoteKillSwitch_overridesLocalConsent() {
        assertFalse(TelemetryConsentState(localConsent = true, remoteAllowed = false).isEnabled)
    }

    @Test
    fun localOptOut_alwaysDisablesTelemetry() {
        assertFalse(TelemetryConsentState(localConsent = false, remoteAllowed = false).isEnabled)
        assertFalse(TelemetryConsentState(localConsent = false, remoteAllowed = true).isEnabled)
    }

    @Test
    fun remoteReenable_restoresConsentWhenUserOptedIn() {
        assertTrue(TelemetryConsentState(localConsent = true, remoteAllowed = true).isEnabled)
    }
}
