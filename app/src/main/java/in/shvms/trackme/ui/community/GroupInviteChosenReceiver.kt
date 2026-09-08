package `in`.shvms.trackme.ui.community

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import `in`.shvms.trackme.analytics.AnalyticsManager

/**
 * TASK-289 — fires `group_invite_destination_chosen` when the user picks a share destination.
 *
 * The event used to fire the instant `shareInvite` ran, which counted *sheet presentations*, not
 * sends. That matters more than it sounds: invite conversion is the success
 * metric for this whole row, and a metric that counts openings cannot tell "nobody shared" apart
 * from "everybody opened the sheet and backed out" — which are opposite problems with opposite
 * fixes.
 *
 * The system delivers `EXTRA_CHOSEN_COMPONENT` here once a target is chosen. That is the closest
 * signal Android offers: it means the user committed to a destination. It cannot observe whether
 * they pressed Send inside the other app, so the event is named after the observable action rather
 * than pretending it proves delivery. Dismissing the sheet delivers nothing at all.
 *
 * Records only that a choice was made. The chosen component is deliberately not read or reported:
 * which app someone invites their friend through is not ours to collect.
 */
class GroupInviteChosenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        // This receiver is explicit and non-exported, but still reject an unexpected action so a
        // future PendingIntent reuse cannot silently manufacture growth events.
        if (intent?.action != ACTION) return
        AnalyticsManager.trackGroupInviteDestinationChosen()
    }

    companion object {
        const val ACTION = "in.shvms.trackme.GROUP_INVITE_CHOSEN"
    }
}
