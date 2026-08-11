package `in`.shvms.trackme.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import `in`.shvms.trackme.MainActivity
import `in`.shvms.trackme.domain.group.AlertPolicy
import `in`.shvms.trackme.ui.localization.AppStrings
import java.util.Locale

/**
 * Telling the group that someone needs them — SCOPE_1.7.2 §3.8, amendment **A38**.
 *
 * **The haptic is the signal; the visual is the content.** They are not alternatives: a buzz with
 * nothing to read is a rider pulling over to find out what happened. The haptic is how you notice;
 * the notification is what you learn.
 *
 * Which is also why the notification is not optional. Haptics do not fire from the background on
 * iOS at all, and on Android a bare `Vibrator` buzz with no visible cause is that same mystery — so
 * both platforms deliver the haptic *through* a notification whenever the app is not foreground,
 * rather than diverging for no gain.
 *
 * ### This is not an SOS channel
 *
 * `1.6.4` removed the SOS trigger and `SEND_SMS`; `1.6.5` retired emergency contacts; `TrackMeApp`
 * still deletes the orphaned `sos_channel` on launch. §5.1 is emphatic that 1.7.2 does not walk that
 * back. This channel has a new id, a group-scoped name, and is user-disablable like any other. The
 * body is **attributed** — *"Ravi set their status to Need help"*, never *"Ravi needs help"* —
 * because the app does not know that; Ravi tapped a button.
 */
class GroupAlertNotifier(private val context: Context) {

    fun ensureChannel(strings: AppStrings) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            strings.groupAlertChannelName,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            enableVibration(true)
            setShowBadge(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Posts the alarm or its resolution.
     *
     * The two are deliberately asymmetric: the alarm interrupts, the resolution informs. They share
     * a **notification id per member**, so a set-then-clear leaves exactly one entry in the shade
     * and a rider who missed the alarm sees the resolution rather than a contradictory pair.
     */
    fun post(
        signal: AlertPolicy.Signal,
        memberUid: String,
        memberName: String,
        statusLabel: String,
        groupName: String?,
        strings: AppStrings,
    ) {
        if (signal == AlertPolicy.Signal.NONE) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val raised = signal == AlertPolicy.Signal.ALERT_RAISED
        val body = String.format(
            Locale.getDefault(),
            if (raised) strings.groupAlertSetBody else strings.groupAlertClearedBody,
            memberName,
            statusLabel,
        )

        val open = PendingIntent.getActivity(
            context,
            memberUid.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(groupName ?: strings.appName)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            // Only the alarm interrupts. The resolution is posted quietly — it is relief, not news
            // that needs to reach past a rider's attention a second time.
            .setPriority(if (raised) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (raised) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_STATUS)
            .setSilent(!raised)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId(memberUid), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the call. Nothing to salvage, and
            // crashing the tracking service over a notification would be far worse than losing it.
        }

        vibrate(raised)
    }

    /**
     * Strong double pulse for the alarm, one short pulse for the resolution.
     *
     * Distinguishable through a jacket pocket without looking, which is the entire point of the
     * haptic being the signal rather than the notification.
     */
    private fun vibrate(raised: Boolean) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(VibratorManager::class.java))?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        } ?: return
        if (!vibrator.hasVibrator()) return

        val effect = if (raised) {
            VibrationEffect.createWaveform(longArrayOf(0, 220, 130, 220), -1)
        } else {
            VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        runCatching { vibrator.vibrate(effect) }
    }

    private fun notificationId(uid: String): Int = NOTIFICATION_ID_BASE + (uid.hashCode() and 0xFFFF)

    companion object {
        /**
         * Deliberately not `sos_channel`, which `TrackMeApp` still deletes on launch. If this reads
         * as an SOS channel to a store reviewer, it is wrong (§5.1).
         */
        const val CHANNEL_ID = "group_status_alerts"
        private const val NOTIFICATION_ID_BASE = 4700
    }
}
