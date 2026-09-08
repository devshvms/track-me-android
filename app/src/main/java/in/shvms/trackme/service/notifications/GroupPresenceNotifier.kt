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
import `in`.shvms.trackme.domain.notifications.GroupPresenceNotice
import `in`.shvms.trackme.ui.localization.getAppStrings
import java.util.Locale

/**
 * SCOPE_1.8.7 §6.1.4 scenario 22 — telling the rider they are still visible to a group.
 *
 * The policy is in [GroupPresenceNotice]; this owns the device half and the "once per group, ever"
 * ledger. That ledger is the only thing between a trust notification and a nuisance, so it is
 * persisted rather than held in memory: a process death between the ride ending and the app being
 * reopened must not turn one notice into two.
 */
object GroupPresenceNotifier {

    const val NOTIFICATION_ID = 4307

    private const val PREFS = "trackme_group_presence_notice"
    private const val KEY_NOTICED_GROUPS = "noticed_group_ids"

    /**
     * The groups the rider has already been told about.
     *
     * Capped, oldest-out. An unbounded set in `SharedPreferences` is a slow leak, and a rider who
     * has been in more than [MAX_REMEMBERED] groups since the last one will accept being told twice
     * about a group they left months ago far more readily than an app that stopped starting.
     */
    private const val MAX_REMEMBERED = 50

    /**
     * Stored as a delimited string rather than a `StringSet`.
     *
     * `SharedPreferences` gives a `Set<String>` back in unspecified order, so an oldest-out cap
     * built on one evicts an arbitrary entry — which here means arbitrarily re-notifying about a
     * group the rider was already told about. The delimiter is a newline because a Firestore
     * document id cannot contain one.
     */
    fun noticedGroupIds(context: Context): Set<String> = orderedNoticedGroupIds(context).toSet()

    private fun orderedNoticedGroupIds(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NOTICED_GROUPS, null)
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    /**
     * Evaluates scenario 22 and, if it holds, says so.
     *
     * Safe to call more than once — the ledger makes it idempotent per group.
     */
    fun notifyIfStillLive(
        context: Context,
        groupId: String?,
        groupName: String?,
        isGroupLive: Boolean,
        isRideActive: Boolean,
    ) {
        val app = context.applicationContext as? TrackMeApp ?: return
        if (!GroupPresenceNotice.shouldNotify(
                activeGroupId = groupId,
                isGroupLive = isGroupLive,
                isRideActive = isRideActive,
                alreadyNoticedGroupIds = noticedGroupIds(context),
            )
        ) return

        val id = groupId ?: return
        remember(context, id)

        val strings = getAppStrings(app.preferencesManager.appLanguage.value)

        // Bulletin first, permission second — the in-app feed is the surface that has to work for
        // someone who denied notification permission, and this row is about a disclosure they are
        // entitled to know about whether or not they let us into their shade.
        app.bulletinStore.add(
            BulletinEntry(
                id = "group-still-live:$id",
                kind = BulletinKind.GROUP_STILL_LIVE,
                createdAtMillis = System.currentTimeMillis(),
                facts = buildMap {
                    groupName?.takeIf { it.isNotBlank() }?.let {
                        put(BulletinEntry.FACT_GROUP_NAME, it)
                    }
                },
            )
        )

        if (!BroadcastSubscription.hasNotificationPermission(context)) return

        NotificationChannels.ensure(context, strings)

        val body = if (!groupName.isNullOrBlank()) {
            String.format(Locale.getDefault(), strings.groupStillLiveBody, groupName)
        } else {
            strings.groupStillLiveBodyNoName
        }

        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                // Deep-links to the group rather than the home screen. "Leave group" has to be one
                // tap from the notification, and a notice about being visible that drops you
                // somewhere you cannot act on it is a notice that raises an alarm and then shrugs.
                .putExtra(EXTRA_OPEN_GROUP, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, NotificationChannels.DATA)
            .setSmallIcon(R.drawable.ic_trackme_logo_transparent)
            .setContentTitle(strings.groupStillLiveTitle)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .addAction(0, strings.groupStillLiveLeave, open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching {
            context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun remember(context: Context, groupId: String) {
        val existing = orderedNoticedGroupIds(context).toMutableList()
        existing.remove(groupId)
        existing.add(groupId)
        val capped = existing.takeLast(MAX_REMEMBERED)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NOTICED_GROUPS, capped.joinToString("\n"))
            .apply()
    }

    fun cancel(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    const val EXTRA_OPEN_GROUP = "open_group_from_presence_notice"
}
