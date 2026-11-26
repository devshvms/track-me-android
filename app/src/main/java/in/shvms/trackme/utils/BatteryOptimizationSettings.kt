package `in`.shvms.trackme.utils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * TASK-383 (v1.9.5): Canonical, Play-Store-compliant battery-optimization status check and
 * Settings launcher shared across onboarding, diagnostics, and interrupted-ride recovery.
 *
 * Deliberately uses [Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS] (falling back to app
 * details) rather than `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, avoiding restricted
 * manifest permissions and Google Play policy declarations.
 */
object BatteryOptimizationSettings {

    /**
     * Returns `true` when TrackMe is already allowlisted ("Unrestricted") from Android Doze / OEM
     * battery optimization, or when [PowerManager] cannot be queried (safe default so we never
     * prompt unnecessarily).
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return runCatching {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        }.getOrDefault(true)
    }

    fun buildSettingsIntent(context: Context): Intent {
        val batteryListIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val hasHandler = runCatching {
            batteryListIntent.resolveActivity(context.packageManager) != null
        }.getOrDefault(false)
        if (hasHandler) return batteryListIntent

        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun open(context: Context): Boolean {
        val openedPrimary = runCatching {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        if (openedPrimary) return true

        return runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
    }

    fun pendingIntentOrNull(context: Context, requestCode: Int): PendingIntent? {
        return runCatching {
            PendingIntent.getActivity(
                context,
                requestCode,
                buildSettingsIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }.getOrNull()
    }
}
