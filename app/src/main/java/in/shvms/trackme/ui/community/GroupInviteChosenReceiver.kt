package `in`.shvms.trackme.ui.community

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import `in`.shvms.trackme.analytics.AnalyticsManager

/**
 * TASK-289 — fires `group_invite_sent` when the user actually picks somewhere to send the invite.
 *
 * The event used to fire the instant `shareInvite` ran, which counted *sheet presentations*, not
 * sends. That matters more than it sounds: `group_invite_sent ÷ group_created` is the success
 * metric for this whole row, and a metric that counts openings cannot tell "nobody shared" apart
 * from "everybody opened the sheet and backed out" — which are opposite problems with opposite
 * fixes.
 *
 * The system delivers `EXTRA_CHOSEN_COMPONENT` here once a target is chosen. That is the closest
 * signal Android offers: it means the user committed to a destination. It still cannot observe
 * whether they pressed send inside the other app — nothing can — so this over-counts slightly
 * against true sends, and under-counts nothing. Dismissing the sheet delivers nothing at all,
 * which is the outcome the old placement got wrong.
 *
 * Records only that a choice was made. The chosen component is deliberately not read or reported:
 * which app someone invites their friend through is not ours to collect.
 */
class GroupInviteChosenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        AnalyticsManager.trackGroupInviteSent()
    }

    companion object {
        const val ACTION = "in.shvms.trackme.GROUP_INVITE_CHOSEN"
    }
}
