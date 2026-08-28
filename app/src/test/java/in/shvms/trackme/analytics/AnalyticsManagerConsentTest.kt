package `in`.shvms.trackme.analytics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// TASK-250 added a third term. These cases are about consent and the remote switch, so they hold
// the environment open to keep testing what they were written to test; the environment term has its
// own cases in TelemetryEnvironmentTest.
class AnalyticsManagerConsentTest {
    @Test
    fun freshInstall_withoutLocalConsent_isDisabled() {
        assertFalse(TelemetryConsentState(localConsent = false, remoteAllowed = true, environmentAllowsDelivery = true).isEnabled)
    }

    @Test
    fun localConsent_onWithRemoteAllowed_isEnabled() {
        assertTrue(TelemetryConsentState(localConsent = true, remoteAllowed = true, environmentAllowsDelivery = true).isEnabled)
    }

    @Test
    fun remoteKillSwitch_overridesLocalConsent() {
        assertFalse(TelemetryConsentState(localConsent = true, remoteAllowed = false, environmentAllowsDelivery = true).isEnabled)
    }

    @Test
    fun localOptOut_alwaysDisablesTelemetry() {
        assertFalse(TelemetryConsentState(localConsent = false, remoteAllowed = false, environmentAllowsDelivery = true).isEnabled)
        assertFalse(TelemetryConsentState(localConsent = false, remoteAllowed = true, environmentAllowsDelivery = true).isEnabled)
    }

    @Test
    fun remoteReenable_restoresConsentWhenUserOptedIn() {
        assertTrue(TelemetryConsentState(localConsent = true, remoteAllowed = true, environmentAllowsDelivery = true).isEnabled)
    }
}
