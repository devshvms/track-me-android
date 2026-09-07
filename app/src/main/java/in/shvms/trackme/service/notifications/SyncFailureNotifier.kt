package `in`.shvms.trackme.service.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import `in`.shvms.trackme.MainActivity
import `in`.shvms.trackme.R
import `in`.shvms.trackme.data.local.BulletinStore
import `in`.shvms.trackme.domain.bulletin.BulletinEntry
import `in`.shvms.trackme.domain.bulletin.BulletinKind
import `in`.shvms.trackme.domain.notifications.SyncFailureNotice
import `in`.shvms.trackme.ui.localization.AppStrings
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * SCOPE_1.8.7 §6.1.5 scenario 23 — the sync-failure notice, and the episode state behind it.
 *
 * Class A. Never rationed by the proactive budget: a backup that broke during a week when a recap
 * went out is still a broken backup.
 *
 * The state is three values in preferences rather than a table, because an episode is a run of
 * failures and nothing about it needs history — only "how many since the last success", "have we
 * said anything about this run", and "when did it last work", which is the one fact the message
 * actually carries.
 */
class SyncFailureNotifier(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Records the outcome of a sync attempt and notifies if this is the moment to.
     *
     * @param unsyncedRideCount rides waiting for the cloud. Zero means the backup is idle rather
     *   than broken, and there is nothing to warn about.
     */
    fun recordAttempt(
        succeeded: Boolean,
        unsyncedRideCount: Int,
        strings: AppStrings,
        bulletin: BulletinStore?,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val before = SyncFailureNotice.Episode(
            consecutiveFailures = prefs.getInt(KEY_FAILURES, 0),
            notified = prefs.getBoolean(KEY_NOTIFIED, false),
        )
        val after = SyncFailureNotice.record(succeeded, before)

        if (succeeded) {
            // A success is what ends an episode — and what makes "once per episode" mean an
            // episode rather than "once, ever".
            prefs.edit()
                .putInt(KEY_FAILURES, 0)
                .putBoolean(KEY_NOTIFIED, false)
                .putLong(KEY_LAST_SUCCESS, nowMillis)
                .apply()
            return
        }

        prefs.edit().putInt(KEY_FAILURES, after.consecutiveFailures).apply()

        if (!SyncFailureNotice.shouldNotify(after.consecutiveFailures, unsyncedRideCount, after.notified)) return

        val since = prefs.getLong(KEY_LAST_SUCCESS, 0L).takeIf { it > 0L }
        val sinceLabel = since?.let {
            DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(it))
        }

        // The bulletin gets the row whether or not a notification can be posted: someone who
        // declined notifications still needs to be able to find out their backup is broken.
        bulletin?.add(
            BulletinEntry(
                // One row per episode, keyed by when it started failing rather than by "now", so a
                // retry that notifies again cannot stack rows about one outage.
                id = "sync-problem:${since ?: 0L}",
                kind = BulletinKind.SYNC_PROBLEM,
                createdAtMillis = nowMillis,
                facts = buildMap {
                    put(BulletinEntry.FACT_UNSYNCED_COUNT, unsyncedRideCount.toString())
                    since?.let { put(BulletinEntry.FACT_SINCE_MILLIS, it.toString()) }
                },
            )
        )

        // Marked reported only once something has actually been shown. Setting it before the
        // permission and date checks below meant a device with no last-success timestamp consumed
        // its whole first failing episode in silence — no row, no notification, and no second
        // chance until a success reset the flag. That is precisely the population that has never
        // had a working backup.
        prefs.edit().putBoolean(KEY_NOTIFIED, true).apply()

        if (!BroadcastSubscription.hasNotificationPermission(context)) return

        NotificationChannels.ensure(context, strings)
        val body = if (sinceLabel != null) {
            String.format(
                Locale.getDefault(), strings.bulletinSyncProblemBody, unsyncedRideCount, sinceLabel
            )
        } else {
            // No date to quote — say the part that is true and useful rather than nothing.
            String.format(
                Locale.getDefault(), strings.bulletinSyncProblemBodyNoDate, unsyncedRideCount
            )
        }
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.DATA)
            .setSmallIcon(R.drawable.ic_trackme_logo_transparent)
            .setContentTitle(strings.bulletinSyncProblem)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching {
            context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val PREFS = "trackme_sync_health"
        const val KEY_FAILURES = "consecutive_failures"
        const val KEY_NOTIFIED = "notified_this_episode"
        const val KEY_LAST_SUCCESS = "last_success_at"
        const val NOTIFICATION_ID = 4303
    }
}
