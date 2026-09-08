package `in`.shvms.trackme.service.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import `in`.shvms.trackme.MainActivity
import `in`.shvms.trackme.R
import `in`.shvms.trackme.TrackMeApp
import `in`.shvms.trackme.domain.bulletin.BulletinEntry
import `in`.shvms.trackme.domain.bulletin.BulletinKind
import `in`.shvms.trackme.domain.notifications.ForgottenRideNotice
import `in`.shvms.trackme.ui.localization.getAppStrings
import java.util.Locale

/**
 * SCOPE_1.8.7 §6.1.1 scenario 4 — asking whether a still, still-recording ride is over.
 *
 * The policy lives in [ForgottenRideNotice]; this is the part that needs a device. It is called
 * from `TrackingService` on each location fix, so everything here is cheap and everything is
 * guarded by the once-per-ride flag the caller owns.
 *
 * ### Two actions, and neither of them is "stop"
 *
 * The notification offers **Finish ride** and **Keep recording**. Finishing is routed through the
 * same stop intent the user's own stop button uses — it does not have a private path that skips
 * the save, because a stop that loses the ride would turn a helpful question into the worst bug in
 * the release. Dismissing the notification does nothing at all, which is the correct behaviour for
 * a question whose honest default is "carry on".
 */
object ForgottenRideNotifier {

    const val NOTIFICATION_ID = 4306

    /** Handled by `TrackingService`; declared here because this file is what refers to it. */
    const val ACTION_FINISH_RIDE = "in.shvms.trackme.action.FINISH_FORGOTTEN_RIDE"
    const val ACTION_KEEP_RECORDING = "in.shvms.trackme.action.KEEP_RECORDING"

    /**
     * Posts the question and records it in the bulletin.
     *
     * @param elapsedMillis the whole ride so far, not the stillness. The rider's question is "how
     *   long has this been running", and the stillness is the *reason* we are asking.
     * @param stillSinceMillis when movement stopped, or null if that could not be established.
     * @param formattedStillSince [stillSinceMillis] as a clock time, already localised by the
     *   caller — `BulletinCopy` cannot format times, and neither can this.
     */
    fun notifyForgottenRide(
        context: Context,
        elapsedMillis: Long,
        stillSinceMillis: Long?,
        formattedStillSince: String?,
    ) {
        val app = context.applicationContext as? TrackMeApp ?: return
        val strings = getAppStrings(app.preferencesManager.appLanguage.value)
        val elapsedMinutes = (elapsedMillis / 60_000L).toInt()

        // The bulletin row goes in before the permission check, and that ordering is the fix for a
        // real defect: the in-app feed is precisely the surface that has to work for someone who
        // denied notification permission, so guarding it behind that permission erases the fallback
        // §6.1.7 promises for exactly the population that depends on it.
        app.bulletinStore.add(
            BulletinEntry(
                id = "forgotten-ride:${stillSinceMillis ?: elapsedMillis}",
                kind = BulletinKind.FORGOTTEN_RIDE,
                createdAtMillis = System.currentTimeMillis(),
                facts = buildMap {
                    put(BulletinEntry.FACT_ELAPSED_MINUTES, elapsedMinutes.toString())
                    stillSinceMillis?.let {
                        put(BulletinEntry.FACT_STILL_SINCE_MILLIS, it.toString())
                    }
                },
            )
        )

        if (!BroadcastSubscription.hasNotificationPermission(context)) return

        NotificationChannels.ensure(context, strings)

        val body = if (formattedStillSince != null) {
            String.format(Locale.getDefault(), strings.forgottenRideBody, elapsedMinutes, formattedStillSince)
        } else {
            String.format(Locale.getDefault(), strings.forgottenRideBodyNoTime, elapsedMinutes)
        }

        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, NotificationChannels.DATA)
            .setSmallIcon(R.drawable.ic_trackme_logo_transparent)
            .setContentTitle(strings.forgottenRideTitle)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .addAction(0, strings.forgottenRideStop, serviceAction(context, ACTION_FINISH_RIDE, 1))
            .addAction(0, strings.forgottenRideKeepGoing, serviceAction(context, ACTION_KEEP_RECORDING, 2))
            .setAutoCancel(true)
            // DEFAULT, not HIGH. A ride recording longer than it should is a data problem the rider
            // has hours to fix, and the channel is the one they were told carries their data.
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        runCatching {
            context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        }
    }

    /** Both actions go back to the tracking service, which is the only thing that may end a ride. */
    private fun serviceAction(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, `in`.shvms.trackme.service.TrackingService::class.java).setAction(action)
        return PendingIntent.getService(
            context,
            NOTIFICATION_ID + requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun cancel(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }
}
