package `in`.shvms.trackme.service.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import `in`.shvms.trackme.MainActivity
import `in`.shvms.trackme.R
import `in`.shvms.trackme.TrackMeApp
import `in`.shvms.trackme.ui.localization.getAppStrings
import java.util.Locale

/**
 * TASK-369: User-facing notifications for Smart Auto-End and Auto-Start Continuous Trip Chaining.
 */
object AutoTripNotifier {

    const val AUTO_TRIP_COMPLETED_NOTIFICATION_ID = 4307
    const val AUTO_TRIP_STARTED_NOTIFICATION_ID = 4308

    fun notifyAutoTripCompleted(
        context: Context,
        personaLabel: String,
        distanceFormatted: String,
    ) {
        val app = context.applicationContext as? TrackMeApp ?: return
        val strings = getAppStrings(app.preferencesManager.appLanguage.value)

        if (!BroadcastSubscription.hasNotificationPermission(context)) return
        NotificationChannels.ensure(context, strings)

        val body = String.format(Locale.getDefault(), strings.notifAutoTripSavedBody, personaLabel, distanceFormatted)

        val openIntent = PendingIntent.getActivity(
            context,
            AUTO_TRIP_COMPLETED_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, NotificationChannels.DATA)
            .setSmallIcon(R.drawable.ic_trackme_logo_transparent)
            .setContentTitle(strings.notifAutoTripSavedTitle)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        runCatching {
            context.getSystemService(NotificationManager::class.java)?.notify(AUTO_TRIP_COMPLETED_NOTIFICATION_ID, notification)
        }
    }

    fun notifyAutoTripStarted(context: Context) {
        val app = context.applicationContext as? TrackMeApp ?: return
        val strings = getAppStrings(app.preferencesManager.appLanguage.value)

        if (!BroadcastSubscription.hasNotificationPermission(context)) return
        NotificationChannels.ensure(context, strings)

        val openIntent = PendingIntent.getActivity(
            context,
            AUTO_TRIP_STARTED_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, NotificationChannels.DATA)
            .setSmallIcon(R.drawable.ic_trackme_logo_transparent)
            .setContentTitle(strings.notifAutoTripStartedTitle)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        runCatching {
            context.getSystemService(NotificationManager::class.java)?.notify(AUTO_TRIP_STARTED_NOTIFICATION_ID, notification)
        }
    }
}
