package `in`.shvms.trackme.service.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import `in`.shvms.trackme.MainActivity
import `in`.shvms.trackme.R
import `in`.shvms.trackme.domain.notifications.RecoveryNotice
import `in`.shvms.trackme.domain.recovery.OrphanedRideRecoveryManager
import `in`.shvms.trackme.ui.localization.AppStrings
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * SCOPE_1.8.7 §6.1.1 scenario 1 — telling someone their interrupted ride was saved.
 *
 * Class A: about the user's data, so it is never rationed by the proactive budget. A ride recovered
 * during a week when a recap already went out is still a ride the user must be told about.
 *
 * The in-app banner already existed (`TrackMeApp._recoveryNotice`) and is kept — but it only fires
 * if the app is opened, and the population that needs this most is the one whose phone died and who
 * has therefore *stopped* expecting the ride to be there. That is the gap the PRD records as a
 * failing criterion, and this closes it.
 */
object RecoveryNotifier {

    /** Stable id: a second recovery replaces the first rather than stacking two data notices. */
    private const val NOTIFICATION_ID = 4301

    fun notify(
        context: Context,
        summary: OrphanedRideRecoveryManager.RecoverySummary,
        strings: AppStrings,
        imperialUnits: Boolean,
    ) {
        val single = summary.recovered.singleOrNull()
        val notice = RecoveryNotice.decide(
            recoveredCount = summary.recoveredCount,
            discardedCount = summary.discardedCount,
            // Formatted here, in the layer that knows the locale and the unit preference — the
            // decision itself stays pure and testable, the same split as ReplayOverlay.
            endedAtLabel = single?.let {
                DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault())
                    .format(Date(it.endTimeMillis))
            },
            distanceLabel = single
                ?.takeIf { it.distanceMeters >= 1.0 }
                ?.let {
                    `in`.shvms.trackme.domain.UnitFormatter.rideDistance(it.distanceMeters, imperialUnits)
                },
        ) ?: return

        if (!BroadcastSubscription.hasNotificationPermission(context)) return
        NotificationChannels.ensure(context, strings)

        val (title, body) = when (notice) {
            is RecoveryNotice.Notice.Many ->
                String.format(Locale.getDefault(), strings.ridesSavedTitle, notice.count) to
                    strings.ridesSavedBody
            is RecoveryNotice.Notice.One ->
                strings.rideSavedTitle to
                    if (notice.endedAtLabel != null && notice.distanceLabel != null) {
                        String.format(
                            Locale.getDefault(),
                            strings.rideSavedBody,
                            notice.endedAtLabel,
                            notice.distanceLabel,
                        )
                    } else {
                        strings.rideSavedBodyPlain
                    }
        }

        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, NotificationChannels.DATA)
            .setSmallIcon(R.drawable.ic_trackme_logo_transparent)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // DEFAULT, not HIGH. The news is good and the ride is not going anywhere — this should
            // be waiting when the phone is next picked up, not interrupt whatever is happening now.
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification)
        }
    }
}
