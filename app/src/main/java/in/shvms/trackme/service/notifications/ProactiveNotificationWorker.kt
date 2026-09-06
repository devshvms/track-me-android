package `in`.shvms.trackme.service.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import `in`.shvms.trackme.MainActivity
import `in`.shvms.trackme.R
import `in`.shvms.trackme.TrackMeApp
import `in`.shvms.trackme.data.local.ProactiveLedger
import `in`.shvms.trackme.domain.UnitFormatter
import `in`.shvms.trackme.domain.notifications.NotificationBudget
import `in`.shvms.trackme.domain.notifications.WeeklyRecapNotice
import `in`.shvms.trackme.ui.localization.getAppStrings
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * SCOPE_1.8.7 §6.1.2 scenario 8 — the weekly recap, delivered to people who do not open the app.
 *
 * ### Why this is a worker and not a foreground check
 *
 * The recap already surfaces in-app, and §6.1.2 records the problem exactly: it is *"currently
 * reachable only by opening the app on a calm Monday, which is exactly the population that needs it
 * least"*. Posting it when the app is already open would not fix that — it would notify the people
 * who were about to see it anyway.
 *
 * So it runs on a schedule. `WorkManager`, inexact, daily: nothing here needs an exact alarm, so
 * `SCHEDULE_EXACT_ALARM` stays undeclared (§6.2 P0) — that permission is scrutinised on both stores
 * and this feature does not earn it. Daily rather than weekly because the budget decides *whether*,
 * not the schedule; a daily check that mostly declines is what lets a recap land the day it becomes
 * eligible rather than up to a week later.
 *
 * ### Every refusal here is a decision, not an early return
 *
 * The worker consults `NotificationBudget` and `WeeklyRecapNotice` and posts at most one thing. It
 * never posts and *then* checks, because `recordProactiveSent` is what closes the week and a send
 * that is not recorded is a cap that does not hold.
 */
class ProactiveNotificationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? TrackMeApp ?: return Result.success()
        return try {
            deliverWeeklyRecap(app)
            Result.success()
        } catch (e: Exception) {
            // A failed proactive notification is not worth a retry storm. The next daily run picks
            // it up, and the recap is still eligible because nothing was recorded.
            app.errorLogger.recordException(e)
            Result.success()
        }
    }

    private fun deliverWeeklyRecap(app: TrackMeApp) {
        if (!BroadcastSubscription.hasNotificationPermission(applicationContext)) return

        val ledger = ProactiveLedger(applicationContext)
        val recap = app.rideStatsStore.pendingWeeklyRecap()
        val now = System.currentTimeMillis()

        if (!WeeklyRecapNotice.shouldNotify(
                recap = recap,
                nowMillis = now,
                lastProactiveSentAtMillis = ledger.lastProactiveSentAtMillis,
                alreadyNotifiedWeekStart = ledger.lastRecapWeekStartEpochDay,
            )
        ) return
        val ready = recap ?: return

        val strings = getAppStrings(app.preferencesManager.appLanguage.value)
        val imperial = app.preferencesManager.unitSystem.value == "imperial"

        val body = String.format(
            Locale.getDefault(),
            strings.weeklyRecapNotificationBody,
            ready.rideCount,
            UnitFormatter.rideDistance(ready.distanceMeters, imperial),
        )

        NotificationChannels.ensure(applicationContext, strings)
        val open = PendingIntent.getActivity(
            applicationContext,
            NOTIFICATION_ID,
            Intent(applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(applicationContext, NotificationChannels.PROGRESS)
            .setSmallIcon(R.drawable.ic_trackme_logo_transparent)
            .setContentTitle(strings.weeklyRecapNotificationTitle)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            // LOW: this is the least urgent thing the app says. A sound for a weekly summary is how
            // a channel people were willing to keep gets turned off.
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        runCatching {
            applicationContext.getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification)
        }.onSuccess {
            // Recorded only on a successful post. Both markers: the shared budget closes the week
            // for every Class C source, and the week marker stops this one recap being announced
            // again if the user never acknowledges it in-app.
            ledger.recordProactiveSent(now)
            ledger.recordRecapNotified(ready.weekStartEpochDay)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 4302
        const val WORK_NAME = "TrackMeProactiveNotifications"

        /**
         * Daily, inexact, and battery-aware. `KEEP` so an already-scheduled worker is not restarted
         * on every launch — re-enqueuing with REPLACE would reset the period each time the app
         * opens, which for a frequently-opened app means the work never actually runs.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ProactiveNotificationWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
