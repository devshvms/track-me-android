package `in`.shvms.trackme.service.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import `in`.shvms.trackme.MainActivity
import `in`.shvms.trackme.R
import `in`.shvms.trackme.TrackMeApp
import `in`.shvms.trackme.data.local.ActivityReminderStore
import `in`.shvms.trackme.data.local.RideHistoryProfileSource
import `in`.shvms.trackme.domain.notifications.ActivityReminder
import `in`.shvms.trackme.ui.localization.getAppStrings
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * SCOPE_1.8.7 §6.1.3 scenario 12a — delivering the reminder the user asked for.
 *
 * ### One-shot and self-re-arming, not periodic
 *
 * `WorkManager` here is inexact by design: `SCHEDULE_EXACT_ALARM` stays undeclared across this
 * release, because nothing in it is worth asking a user for the permission that also powers alarm
 * clocks. But inexact must not mean unanchored — see [ActivityReminderSchedule] for why a daily
 * periodic worker cannot deliver this reminder at all, for anyone whose device settles into an
 * early daily slot.
 *
 * So the work is armed for the next occurrence of the user's slot and re-armed after each firing.
 * The reminder still lands *within* a window rather than on the minute; the window is now anchored
 * to the time they picked. For "remind me to walk on Saturday morning" that is the right trade;
 * for anything where the minute mattered, this would be the wrong primitive entirely.
 *
 * ### Class B — outside the proactive budget
 *
 * This deliberately does not consult [NotificationBudget]. §6.0's budget limits what the *app*
 * decides to say, and spending a rider's weekly allowance on their own alarm would mean the app
 * punishing them for using a feature they configured. The safeguard that keeps that honest is that
 * nothing here can fire unless [ActivityReminderStore] holds an enabled reminder, and nothing
 * writes that except the settings screen.
 */
class ActivityReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? TrackMeApp ?: return Result.success()
        runCatching { deliver(app) }
        // Re-arm unconditionally, and outside the runCatching above. If delivery threw, next
        // Saturday must still be scheduled — a reminder that silently stops after one bad week is
        // indistinguishable, to the user, from one they never set.
        runCatching { schedule(applicationContext) }
        // Always success: this fires at a moment that has passed by the time anything could be
        // retried, and a retry would only deliver the reminder at the wrong time.
        return Result.success()
    }

    private fun deliver(app: TrackMeApp) {
        val store = ActivityReminderStore(applicationContext)
        val settings = store.settings.value
        if (!settings.enabled) return

        val now = Calendar.getInstance(TimeZone.getDefault())
        val nowEpochDay = now.timeInMillis / 86_400_000L

        if (!ActivityReminder.shouldFire(
                settings = settings,
                nowDayOfWeek = RideHistoryProfileSource.isoDayOfWeek(now.get(Calendar.DAY_OF_WEEK)),
                nowEpochDay = nowEpochDay,
                lastFiredEpochDay = store.lastFiredEpochDay,
            )
        ) return

        store.recordFired(nowEpochDay)

        if (!BroadcastSubscription.hasNotificationPermission(applicationContext)) return

        val strings = getAppStrings(app.preferencesManager.appLanguage.value)
        NotificationChannels.ensure(applicationContext, strings)

        // `personaLabel`, never `displayName` — the latter is an English enum label that reads as
        // "BikeDrive" to a user and is never translated.
        val personaLabel = runCatching {
            strings.personaLabel(`in`.shvms.trackme.domain.model.RidePersona.valueOf(settings.persona))
        }.getOrNull() ?: strings.personaLabel(`in`.shvms.trackme.domain.model.RidePersona.AUTO)

        val open = PendingIntent.getActivity(
            applicationContext,
            NOTIFICATION_ID,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(applicationContext, NotificationChannels.REMINDERS)
            .setSmallIcon(R.drawable.ic_trackme_logo_transparent)
            .setContentTitle(String.format(Locale.getDefault(), strings.reminderNotificationTitle, personaLabel))
            .setContentText(strings.reminderNotificationBody)
            .setContentIntent(open)
            .setAutoCancel(true)
            // DEFAULT, unlike every Class C notice in this release, and the difference is the
            // point: the user picked this moment, so arriving quietly enough to be missed would
            // fail the only thing they asked for.
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        runCatching {
            applicationContext.getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 4308
        const val WORK_NAME = "TrackMeActivityReminder"

        /**
         * Arms the next firing, or cancels everything if the reminder is off.
         *
         * One-shot with an initial delay rather than periodic — see [ActivityReminderSchedule] for
         * why a daily periodic worker cannot deliver this at all. `REPLACE` because every call is
         * either a settings change or a re-arm after firing, and in both cases the previously
         * computed delay is stale by construction.
         */
        fun schedule(context: Context) {
            val settings = ActivityReminderStore(context).settings.value
            val delay = ActivityReminderSchedule.millisUntilNext(settings, System.currentTimeMillis())
            if (delay == null) {
                cancel(context)
                return
            }
            val request = OneTimeWorkRequestBuilder<ActivityReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        /** Called when the user switches the reminder off, so nothing is left waking up for it. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
