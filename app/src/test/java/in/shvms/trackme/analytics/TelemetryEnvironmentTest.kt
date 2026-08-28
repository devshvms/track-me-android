package `in`.shvms.trackme.analytics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-250. The environment gate decides whether a build may reach PostHog and Crashlytics at all.
 *
 * Worth testing rather than eyeballing because both failure directions are silent: a false negative
 * quietly seeds the production funnel with developer sessions, and a false positive quietly drops a
 * real rider's data with no error anywhere. Neither shows up until someone questions a number.
 */
class TelemetryEnvironmentTest {

    // Real fingerprints, kept verbatim so this reads as evidence rather than as invention.
    private val pixelHardware = "zuma"
    private val pixelFingerprint = "google/husky/husky:14/AP1A.240405.002/11480754:user/release-keys"
    private val emulatorFingerprint =
        "google/sdk_gphone64_arm64/emu64a:14/UE1A.230829.036/11228894:userdebug/dev-keys"

    @Test
    fun `a release build on a real device delivers`() {
        assertTrue(telemetryAllowsDelivery(isDebugBuild = false, isEmulator = false))
    }

    @Test
    fun `a debug build never delivers, device or not`() {
        assertFalse(telemetryAllowsDelivery(isDebugBuild = true, isEmulator = false))
        assertFalse(telemetryAllowsDelivery(isDebugBuild = true, isEmulator = true))
    }

    @Test
    fun `a release build on an emulator does not deliver`() {
        // Not hypothetical: the Play publish workflow runs a release launch smoke test on an
        // emulator, so without this every publish would post a synthetic session.
        assertFalse(telemetryAllowsDelivery(isDebugBuild = false, isEmulator = true))
    }

    @Test
    fun `a real device is not mistaken for an emulator`() {
        assertFalse(
            isEmulatorBuild(
                fingerprint = pixelFingerprint,
                model = "Pixel 8 Pro",
                manufacturer = "Google",
                brand = "google",
                device = "husky",
                product = "husky",
                hardware = pixelHardware,
            )
        )
    }

    @Test
    fun `the android emulator is caught by hardware alone`() {
        // HARDWARE is the load-bearing check: goldfish and ranchu are the QEMU machine names the
        // emulator has used throughout, and no OEM would plausibly ship them. Everything else here
        // is deliberately blanked to prove this check stands on its own.
        assertTrue(
            isEmulatorBuild(
                fingerprint = "", model = "", manufacturer = "",
                brand = "", device = "", product = "", hardware = "ranchu",
            )
        )
        assertTrue(
            isEmulatorBuild(
                fingerprint = "", model = "", manufacturer = "",
                brand = "", device = "", product = "", hardware = "goldfish",
            )
        )
    }

    @Test
    fun `a real emulator fingerprint is caught`() {
        assertTrue(
            isEmulatorBuild(
                fingerprint = emulatorFingerprint,
                model = "sdk_gphone64_arm64",
                manufacturer = "Google",
                brand = "google",
                device = "emu64a",
                product = "sdk_gphone64_arm64",
                hardware = "ranchu",
            )
        )
    }

    @Test
    fun `third-party emulators that are not QEMU are still caught`() {
        assertTrue(
            isEmulatorBuild(
                fingerprint = "generic/vbox86p/vbox86p:9/PI/1:user/release-keys",
                model = "Custom Phone", manufacturer = "Genymotion",
                brand = "generic", device = "vbox86p", product = "vbox86p", hardware = "vbox86",
            )
        )
    }

    @Test
    fun `the environment term can only ever subtract`() {
        // It must never grant delivery consent did not. Every combination where consent or the
        // remote switch is off stays off regardless of environment.
        for (env in listOf(true, false)) {
            assertFalse(TelemetryConsentState(localConsent = false, remoteAllowed = true, environmentAllowsDelivery = env).isEnabled)
            assertFalse(TelemetryConsentState(localConsent = true, remoteAllowed = false, environmentAllowsDelivery = env).isEnabled)
        }
        assertFalse(
            "consent alone is not enough from a debug build",
            TelemetryConsentState(localConsent = true, remoteAllowed = true, environmentAllowsDelivery = false).isEnabled
        )
        assertTrue(
            TelemetryConsentState(localConsent = true, remoteAllowed = true, environmentAllowsDelivery = true).isEnabled
        )
    }
}
