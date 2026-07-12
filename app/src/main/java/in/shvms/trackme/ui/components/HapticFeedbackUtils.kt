package `in`.shvms.trackme.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Single-responsibility utility for delivering physical haptic feedback and vibrations
 * across UI interactions.
 *
 * Ensures consistent tactile feedback across interactive components (buttons, sliders, drawers)
 * while abstracting legacy SDK API differences.
 */
object HapticFeedbackUtils {

    /**
     * Triggers a crisp physical vibration pulse using [Vibrator].
     *
     * @param context Application or Activity context.
     * @param durationMs Duration of the vibration in milliseconds (default: 40ms).
     * @param amplitude Intensity amplitude from 1..255 or [VibrationEffect.DEFAULT_AMPLITUDE].
     */
    fun triggerPhysicalVibrate(
        context: Context,
        durationMs: Long = 40L,
        amplitude: Int = VibrationEffect.DEFAULT_AMPLITUDE
    ) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (e: Exception) {
            // Ignore security or hardware vibration exceptions on unsupported devices
        }
    }
}
