package `in`.shvms.trackme.support

/** Plain, permission-free support context. Deliberately contains no location or ride data. */
data class SupportDiagnosticsInput(
    val appVersion: String,
    val androidVersion: String,
    val device: String,
    val appLanguage: String,
    val deviceLocale: String,
    val units: String,
    val installSource: String,
    val locationPermission: String,
    val notificationPermission: String,
    val batteryOptimization: String,
    val signedIn: Boolean
)

object SupportDiagnostics {
    fun render(input: SupportDiagnosticsInput): String = buildString {
        appendLine("App version: ${input.appVersion}")
        appendLine("Android version: ${input.androidVersion}")
        appendLine("Device: ${input.device}")
        appendLine("App language: ${input.appLanguage}")
        appendLine("Device locale: ${input.deviceLocale}")
        appendLine("Units: ${input.units}")
        appendLine("Install source: ${input.installSource}")
        appendLine("Location permission: ${input.locationPermission}")
        appendLine("Notification permission: ${input.notificationPermission}")
        appendLine("Battery optimization: ${input.batteryOptimization}")
        append("Signed in: ${input.signedIn}")
    }
}
