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

data class SupportDiagnosticsLabels(
    val appVersion: String = "App version",
    val androidVersion: String = "Android version",
    val device: String = "Device",
    val appLanguage: String = "App language",
    val deviceLocale: String = "Device locale",
    val units: String = "Units",
    val installSource: String = "Install source",
    val locationPermission: String = "Location permission",
    val notificationPermission: String = "Notification permission",
    val batteryOptimization: String = "Battery optimization",
    val signedIn: String = "Signed in"
)

object SupportDiagnostics {
    fun render(input: SupportDiagnosticsInput, labels: SupportDiagnosticsLabels = SupportDiagnosticsLabels()): String = buildString {
        appendLine("${labels.appVersion}: ${input.appVersion}")
        appendLine("${labels.androidVersion}: ${input.androidVersion}")
        appendLine("${labels.device}: ${input.device}")
        appendLine("${labels.appLanguage}: ${input.appLanguage}")
        appendLine("${labels.deviceLocale}: ${input.deviceLocale}")
        appendLine("${labels.units}: ${input.units}")
        appendLine("${labels.installSource}: ${input.installSource}")
        appendLine("${labels.locationPermission}: ${input.locationPermission}")
        appendLine("${labels.notificationPermission}: ${input.notificationPermission}")
        appendLine("${labels.batteryOptimization}: ${input.batteryOptimization}")
        append("${labels.signedIn}: ${input.signedIn}")
    }
}
