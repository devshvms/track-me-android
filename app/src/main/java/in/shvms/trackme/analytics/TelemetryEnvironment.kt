package `in`.shvms.trackme.analytics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import `in`.shvms.trackme.BuildConfig

/**
 * TASK-250 / TASK-330, shvm: whether *this build, on this machine* may deliver telemetry at all.
 *
 * A third gate beside consent and the remote kill switch, and the only one of the three that is a
 * property of the build rather than of the user. It exists because every debug run, every emulator
 * session and every CI smoke test was landing in the same PostHog project and the same Crashlytics
 * app as real riders. That is not a privacy problem — nobody's data leaks — it is a data-quality
 * one, and the damage is quiet: developer sessions inflate counts, the funnel gets shaped by runs
 * that were never real usage, and a metric nobody trusts is worse than a metric nobody has.
 *
 * **This can only ever subtract.** It never grants delivery the user did not consent to, which is
 * why it composes with the other two by `&&` rather than replacing them.
 */
object TelemetryEnvironment {

    private val isEmulator: Boolean by lazy {
        isEmulatorBuild(
            fingerprint = Build.FINGERPRINT,
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            device = Build.DEVICE,
            product = Build.PRODUCT,
            hardware = Build.HARDWARE,
        )
    }

    @Volatile
    private var isTestLabDevice: Boolean = false

    /**
     * Reads [Settings.System] (`firebase.test.lab`) and [ActivityManager] test-harness flags once
     * when the [Context] becomes available, before Crashlytics or PostHog starts.
     *
     * Google Play Pre-Launch Report and Firebase Test Lab run on physical farm devices (real OEM
     * `Build.HARDWARE` / `Build.FINGERPRINT`), so [isEmulatorBuild] alone cannot catch them.
     */
    fun configure(context: Context) {
        val testLabSetting = runCatching {
            Settings.System.getString(context.contentResolver, "firebase.test.lab")
        }.getOrNull()
        val monkeyOrHarness = runCatching {
            ActivityManager.isUserAMonkey() ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ActivityManager.isRunningInUserTestHarness())
        }.getOrDefault(false)
        isTestLabDevice = isTestLabEnvironment(
            firebaseTestLabSetting = testLabSetting,
            isMonkeyOrTestHarness = monkeyOrHarness,
        )
    }

    val allowsDelivery: Boolean
        get() = telemetryAllowsDelivery(
            isDebugBuild = BuildConfig.DEBUG,
            isEmulator = isEmulator,
            isTestLab = isTestLabDevice,
        )

    /** Human-readable reason, for the one log line that explains a silent analytics build. */
    val suppressionReason: String?
        get() = when {
            allowsDelivery -> null
            BuildConfig.DEBUG -> "debug build"
            isTestLabDevice -> "test lab / automated harness"
            else -> "emulator"
        }
}

/**
 * The rule itself, separated from `Build` so it can be tested on the JVM.
 *
 * Deliberately not "release builds only": a release build running on an emulator or a Firebase
 * Test Lab / Play Pre-Launch Report crawler is still not a rider, and the Play publish workflow
 * runs both as launch checks.
 */
internal fun telemetryAllowsDelivery(
    isDebugBuild: Boolean,
    isEmulator: Boolean,
    isTestLab: Boolean = false,
): Boolean = !isDebugBuild && !isEmulator && !isTestLab

/**
 * Detects Google Play Pre-Launch Report / Firebase Test Lab (`firebase.test.lab == "true"`) and
 * Android monkey / user-test-harness runs.
 */
internal fun isTestLabEnvironment(
    firebaseTestLabSetting: String?,
    isMonkeyOrTestHarness: Boolean = false,
): Boolean =
    firebaseTestLabSetting.equals("true", ignoreCase = true) || isMonkeyOrTestHarness

/**
 * Emulator detection from `Build` fields, passed in rather than read, so this is JVM-testable.
 *
 * `HARDWARE` is the load-bearing check — `goldfish` and `ranchu` are the QEMU machine names the
 * Android emulator has used for its whole history, and unlike the fingerprint they are not
 * something a real OEM would plausibly ship. The rest are belt-and-braces for the third-party
 * emulators (Genymotion, BlueStacks) that do not use QEMU.
 *
 * A false positive costs a lost analytics session on a genuinely odd device; a false negative puts
 * synthetic data in the production funnel. The bias here is deliberately toward the former.
 */
internal fun isEmulatorBuild(
    fingerprint: String,
    model: String,
    manufacturer: String,
    brand: String,
    device: String,
    product: String,
    hardware: String,
): Boolean {
    val hw = hardware.lowercase()
    if (hw.contains("goldfish") || hw.contains("ranchu") || hw.contains("vbox")) return true
    if (fingerprint.startsWith("generic") || fingerprint.contains("vbox") ||
        fingerprint.contains("emulator") || fingerprint.contains("test-keys")
    ) return true
    if (model.contains("google_sdk", ignoreCase = true) ||
        model.contains("Emulator", ignoreCase = true) ||
        model.contains("Android SDK built for", ignoreCase = true)
    ) return true
    if (manufacturer.contains("Genymotion", ignoreCase = true)) return true
    if (brand.startsWith("generic", ignoreCase = true) && device.startsWith("generic", ignoreCase = true)) return true
    if (product.equals("google_sdk", ignoreCase = true) ||
        product.contains("sdk_gphone", ignoreCase = true) ||
        product.contains("emulator", ignoreCase = true) ||
        product.contains("simulator", ignoreCase = true)
    ) return true
    return false
}
