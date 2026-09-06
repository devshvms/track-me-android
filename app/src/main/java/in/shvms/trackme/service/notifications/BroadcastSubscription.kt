package `in`.shvms.trackme.service.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import `in`.shvms.trackme.utils.logger.ErrorLogger

/**
 * SCOPE_1.8.7 §6.3 — subscribing this install to operator broadcasts.
 *
 * ### Why a topic, and why that is the privacy story
 *
 * The obvious design is a `push_tokens` collection: every install registers its FCM token, the
 * server keeps a row per device, and a send fans out over them. That means holding a **device
 * identifier for every user**, declaring it in Data Safety and App Privacy on both stores,
 * deleting it on sign-out and again on account deletion, and never letting the two paths drift.
 *
 * A topic needs none of it. The client subscribes itself; the server addresses `broadcasts` and
 * never learns who is listening. There is nothing to declare, nothing to retain, and nothing to
 * delete — and per-user targeting becomes *impossible* rather than merely forbidden, which is a
 * better way to keep a promise than remembering to.
 *
 * The honest cost: no delivery receipts and no per-user retry. For "the build you are running has
 * a defect" that is the right trade, and the foreground read of the `broadcasts` collection covers
 * anyone the push missed.
 *
 * ### Permission is the subscription
 *
 * [sync] is called on every launch and follows the OS permission rather than a preference of our
 * own. Someone who turns notifications off in system settings has said what they mean; keeping
 * them subscribed so we can deliver "silently" would be exactly the trick this app does not play.
 *
 * TASK-284's rule still holds and is why this never asks: a broadcast arriving must not trigger a
 * permission request. The subscription follows permission; it never solicits it.
 */
object BroadcastSubscription {

    /** Must match `BROADCAST_TOPIC` in `api/admin/broadcast.ts`. */
    const val TOPIC = "broadcasts"

    /**
     * Aligns the topic subscription with the OS notification permission.
     *
     * Both calls are idempotent on FCM's side, so running this on every launch is cheap and is the
     * only thing that recovers a subscription after a reinstall, a restore, or the user turning
     * notifications back on without opening any of our settings.
     */
    fun sync(context: Context, errorLogger: ErrorLogger?) {
        val messaging = runCatching { FirebaseMessaging.getInstance() }.getOrNull() ?: return
        val task = if (hasNotificationPermission(context)) {
            messaging.subscribeToTopic(TOPIC)
        } else {
            messaging.unsubscribeFromTopic(TOPIC)
        }
        task.addOnFailureListener { error ->
            // Not fatal and not worth a user-facing message: the next launch retries, and the
            // foreground read of `broadcasts` means nothing is actually missed in the meantime.
            errorLogger?.recordException(error)
        }
    }

    /**
     * Below Android 13 there is no runtime permission and notifications are on unless the user
     * turned the app's notifications off entirely — which `areNotificationsEnabled` would report,
     * but which is not what this gate is for. Keeping it to the runtime permission keeps the rule
     * the same one TASK-284 reasons about.
     */
    fun hasNotificationPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            true
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }
}
