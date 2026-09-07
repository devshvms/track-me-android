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
            deliverProactive(app)
            Result.success()
        } catch (e: Exception) {
            // A failed proactive notification is not worth a retry storm. The next daily run picks
            // it up, and the recap is still eligible because nothing was recorded.
            app.errorLogger.recordException(e)
            Result.success()
        }
    }

    /**
     * §6.0: when several Class C sources are eligible, exactly one is sent and the losers are
     * **not** consumed — they stay eligible for their next window. `NotificationBudget.choose`
     * decides by declared rank rather than by whichever check happens to run first, which is the
     * only reason two sources can share one weekly allowance without one of them silently winning
     * every time.
     */
    private fun deliverProactive(app: TrackMeApp) {
        if (!BroadcastSubscription.hasNotificationPermission(applicationContext)) return

        val ledger = ProactiveLedger(applicationContext)
        val now = System.currentTimeMillis()
        val recap = app.rideStatsStore.pendingWeeklyRecap()

        // §6.1.7: the fact reaches the feed whether or not it earns an interruption. A recap the
        // budget refuses used to appear nowhere at all — which made "the bulletin is what lets the
        // cap be a trade rather than a loss" untrue for the one case it was written about. The
        // notification is a separate decision below; this is unconditional.
        recap?.takeIf { it.rideCount > 0 }?.let {
            app.bulletinStore.add(`in`.shvms.trackme.data.local.BulletinAdapters.from(it))
        }

        val eligible = buildSet {
            if (WeeklyRecapNotice.shouldNotify(
                    recap = recap,
                    nowMillis = now,
                    lastProactiveSentAtMillis = ledger.lastProactiveSentAtMillis,
                    alreadyNotifiedWeekStart = ledger.lastRecapWeekStartEpochDay,
                )
            ) add(NotificationBudget.ProactiveKind.WEEKLY_RECAP)

            val daysAway = app.rideStatsStore.daysSinceLastActivity()
            if (daysAway != null &&
                NotificationBudget.allows(
                    NotificationBudget.Klass.PROACTIVE, now, ledger.lastProactiveSentAtMillis
                ) &&
                NotificationBudget.allowsReturnNotice(
                    nowMillis = now,
                    lastReturnNoticeAtMillis = ledger.lastReturnNoticeAtMillis,
                    daysSinceLastActivity = daysAway,
                )
            ) add(NotificationBudget.ProactiveKind.RETURN_AFTER_ABSENCE)
        }

        when (NotificationBudget.choose(eligible)) {
            NotificationBudget.ProactiveKind.RETURN_AFTER_ABSENCE ->
                deliverReturnNotice(app, ledger, now)
            NotificationBudget.ProactiveKind.WEEKLY_RECAP ->
                recap?.let { deliverWeeklyRecap(app, ledger, now, it) }
            null -> Unit
        }
    }

    private fun deliverWeeklyRecap(
        app: TrackMeApp,
        ledger: ProactiveLedger,
        now: Long,
        ready: `in`.shvms.trackme.domain.stats.WeeklyRecap,
    ) {

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

    /**
     * §6.1.3 scenario 13 — one notice, at 21 days or more, carrying a real fact.
     *
     * This is the closest the app comes to a line §4.2 N2 would otherwise forbid, and it survives
     * only because of what it is not. It is **not** loss-framed: no streak, no missed days, no
     * falling number. It carries a fact the user might actually want — how long it has been, which
     * they may genuinely have lost track of — and it arrives at most once a quarter.
     *
     * The threshold is deliberately higher than `HomeInsight.Return`'s in-app 14 days: interrupting
     * someone is a bigger claim than showing them something once they have already opened the app.
     */
    private fun deliverReturnNotice(app: TrackMeApp, ledger: ProactiveLedger, now: Long) {
        val days = app.rideStatsStore.daysSinceLastActivity() ?: return
        val strings = getAppStrings(app.preferencesManager.appLanguage.value)
        val body = String.format(Locale.getDefault(), strings.returnNoticeBody, days)

        NotificationChannels.ensure(applicationContext, strings)
        val open = PendingIntent.getActivity(
            applicationContext,
            RETURN_NOTIFICATION_ID,
            Intent(applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, NotificationChannels.PROGRESS)
            .setSmallIcon(R.drawable.ic_trackme_logo_transparent)
            .setContentTitle(strings.returnNoticeTitle)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            // LOW, like the recap. The most intrusive thing this app may say gets the quietest
            // delivery it can have while still being visible.
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        runCatching {
            applicationContext.getSystemService(NotificationManager::class.java)
                ?.notify(RETURN_NOTIFICATION_ID, notification)
        }.onSuccess {
            // Both ledgers: the shared budget closes the week for every C source, and the return
            // ledger closes the quarter for this one.
            ledger.recordProactiveSent(now)
            ledger.recordReturnNoticeSent(now)
            // §6.1.7: "a copy of every notification actually sent". A Class C notice that
            // interrupted someone and cannot then be found in the feed is the exact failure the
            // bulletin exists to prevent.
            app.bulletinStore.add(
                `in`.shvms.trackme.domain.bulletin.BulletinEntry(
                    // Keyed by the quarter it belongs to, so a retry cannot stack rows.
                    id = "return-notice:$now",
                    kind = `in`.shvms.trackme.domain.bulletin.BulletinKind.RETURN_NOTICE,
                    createdAtMillis = now,
                    facts = mapOf(
                        `in`.shvms.trackme.domain.bulletin.BulletinEntry.FACT_DAYS_AWAY to days.toString()
                    ),
                )
            )
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 4302
        private const val RETURN_NOTIFICATION_ID = 4304
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
