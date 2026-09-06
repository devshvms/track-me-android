package `in`.shvms.trackme.service.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import `in`.shvms.trackme.MainActivity
import `in`.shvms.trackme.R
import `in`.shvms.trackme.TrackMeApp
import `in`.shvms.trackme.data.local.BroadcastStore
import `in`.shvms.trackme.domain.notifications.OperatorBroadcast
import `in`.shvms.trackme.ui.localization.getAppStrings

/**
 * SCOPE_1.8.7 §6.3 — receives operator broadcasts.
 *
 * The only place in TrackMe where content from the network becomes a notification, which is why
 * almost all of it is validation.
 *
 * ### Why the payload is data-only
 *
 * The endpoint sends no `notification` block on purpose. A notification payload is rendered by the
 * system **before this class runs**, so an unvalidated string from the network would reach a
 * HIGH-importance channel with no parser in the way — and the closed tag vocabulary, the length
 * limits and the https check would all be decoration. Data-only means we validate first and post
 * second, every time, in both foreground and background.
 *
 * ### Dropping is safe; rendering something wrong is not
 *
 * Anything [OperatorBroadcast.parse] refuses is discarded silently. The same broadcast is also in
 * the `broadcasts` collection and is read on next foreground, so a dropped push costs a delay,
 * while a rendered malformed one costs the channel the credibility that makes it worth having.
 */
class TrackMeMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val broadcast = OperatorBroadcast.parse(message.data) ?: return

        // Not true for this build. An update notice telling someone already on the fixed version to
        // update is noise, and noise on this channel is how people learn to swipe away the one
        // message that mattered.
        if (!broadcast.appliesTo(currentVersionCode())) return

        val app = applicationContext as? TrackMeApp
        val store = app?.broadcastStore ?: BroadcastStore(applicationContext)

        // The same broadcast genuinely arrives twice — once by push, once by the foreground read.
        // Only the first arrival may interrupt.
        if (!store.store(broadcast)) return

        // Follows the OS permission rather than assuming: a push can arrive in the window between
        // the user revoking notifications and FCM processing the unsubscribe. The broadcast is
        // already stored above, so it still reaches them in the app — silently, which is what
        // revoking permission asked for.
        if (!BroadcastSubscription.hasNotificationPermission(applicationContext)) return

        post(broadcast)
    }

    override fun onNewToken(token: String) {
        // Nothing is done with the token, deliberately — see BroadcastSubscription. Re-subscribing
        // is still required: a rotated token is not carried over to existing topic subscriptions.
        BroadcastSubscription.sync(applicationContext, (applicationContext as? TrackMeApp)?.errorLogger)
    }

    private fun post(broadcast: OperatorBroadcast) {
        val strings = getAppStrings(
            (applicationContext as? TrackMeApp)?.preferencesManager?.appLanguage?.value ?: "en"
        )
        NotificationChannels.ensure(applicationContext, strings)

        val open = PendingIntent.getActivity(
            applicationContext,
            broadcast.id.hashCode(),
            Intent(applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(applicationContext, NotificationChannels.OPERATOR)
            .setSmallIcon(R.drawable.ic_trackme_logo_transparent)
            .setContentTitle(broadcast.title)
            .setContentText(broadcast.body)
            // The body is up to 480 characters and the collapsed line shows far less. Without
            // BigTextStyle the operator writes "what is wrong, what to do, when it is fixed" and
            // the reader gets the first clause.
            .setStyle(NotificationCompat.BigTextStyle().bigText(broadcast.body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        // Notification id from the broadcast id, so a duplicate delivery replaces rather than
        // stacks, and two different broadcasts never collapse into one.
        runCatching {
            applicationContext.getSystemService(NotificationManager::class.java)
                ?.notify(broadcast.id.hashCode(), notification)
        }
    }

    private fun currentVersionCode(): Int = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            info.versionCode
        }
    }.getOrDefault(Int.MAX_VALUE)
    // MAX_VALUE on failure: a device whose own version we cannot read must not be told to update
    // to fix a bug it may not have. Silence is the safe direction for a message about correctness.
}
