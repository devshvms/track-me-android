package `in`.shvms.trackme.service.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import `in`.shvms.trackme.ui.localization.AppStrings

/**
 * SCOPE_1.8.7 §6.2 — the channels Track 2 adds, and what each one promises.
 *
 * ### Why separate channels rather than one "TrackMe" channel
 *
 * A channel is the only control Android gives a user that we cannot take away: they can turn one
 * off and keep the rest, from system settings, without uninstalling and without asking us. Putting
 * everything on one channel makes the only available answer to "stop telling me about my level" the
 * same as the answer to "stop telling me my ride was saved" — so people either tolerate the noise
 * or lose the notifications that actually matter. Both outcomes are ours to have caused.
 *
 * So the split is by **what the user would want to switch off independently**, not by which part of
 * the codebase posts it:
 *
 * - [REMINDERS] — things the user asked for at a time they chose (Class B). Someone who sets a
 *   Saturday walk reminder and later wants it gone should not have to find the setting we buried.
 * - [PROGRESS] — the app deciding to speak (Class C). This is the one people will turn off, and
 *   they are entitled to. It is also the one whose value survives being switched off, because the
 *   bulletin still has everything in it.
 * - [DATA] — sync failures, exports, anything about the user's data being safe (Class A). Named so
 *   that turning it off reads like the mistake it is.
 * - [OPERATOR] — §6.3 broadcasts: this build has a defect, sync is down. High importance because
 *   it is only ever used when something is wrong. Nothing promotional may be sent on it, ever, and
 *   `BroadcastTag` is a closed vocabulary so nothing promotional *can* be.
 *
 * The three pre-existing channels — tracking, sync, group alerts — are untouched. Renaming a live
 * channel id resets everyone's preference silently, which is a worse outcome than an inconsistent
 * naming scheme.
 */
object NotificationChannels {

    /** Class B. The user asked for these, at a time they picked. */
    const val REMINDERS = "reminders"

    /** Class C. The app decided to speak. One per week, budget-enforced. */
    const val PROGRESS = "your_progress"

    /** Class A. Anything about whether the user's data is safe. */
    const val DATA = "sync_and_data"

    /** Class D. Operator broadcasts — §6.3. Only ever used when something is wrong. */
    const val OPERATOR = "app_notices"

    /**
     * Creates the channels this release adds. Idempotent: `createNotificationChannel` on an
     * existing id updates only the name and description, never the user's importance choice or
     * their decision to disable it, so this is safe to call on every launch.
     */
    fun ensure(context: Context, strings: AppStrings) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                channel(
                    REMINDERS,
                    strings.channelRemindersName,
                    strings.channelRemindersDescription,
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
                // LOW: a weekly recap is worth a line in the shade, never a sound. It is the least
                // urgent thing the app says and the most frequent of the proactive class, so it
                // gets the quietest treatment that is still visible.
                channel(
                    PROGRESS,
                    strings.channelProgressName,
                    strings.channelProgressDescription,
                    NotificationManager.IMPORTANCE_LOW,
                ),
                channel(
                    DATA,
                    strings.channelDataName,
                    strings.channelDataDescription,
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
                // HIGH is defensible only because of the promotional ban: this channel carries
                // "the build you are running has a defect" and nothing else. The moment it carries
                // anything else, the importance is a dark pattern.
                channel(
                    OPERATOR,
                    strings.channelOperatorName,
                    strings.channelOperatorDescription,
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        )
    }

    // @RequiresApi rather than a second Build.VERSION check: `ensure` already guards the only call
    // path, but lint cannot see that across a private function boundary, and adding a redundant
    // runtime check to satisfy it would leave a branch that can never be taken and never be tested.
    @RequiresApi(Build.VERSION_CODES.O)
    private fun channel(id: String, name: String, description: String, importance: Int) =
        NotificationChannel(id, name, importance).apply { this.description = description }
}
