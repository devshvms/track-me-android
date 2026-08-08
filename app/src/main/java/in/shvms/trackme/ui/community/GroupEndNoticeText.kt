package `in`.shvms.trackme.ui.community

import `in`.shvms.trackme.data.remote.GroupEndNotice
import `in`.shvms.trackme.data.remote.GroupEndReason
import `in`.shvms.trackme.ui.localization.AppStrings

/**
 * What to tell a member whose group stopped without them doing anything — SCOPE_1.7.0 §8.
 *
 * §8 asks for a *"clear notice"* on three separate rows, and they are not the same sentence:
 *
 * - Leader ends, or the leader's device dies and the TTL fires → *"This group has ended."*
 * - **TTL expires mid-ride** → the notice must also say the ride is still going. A map that goes
 *   blank while you are recording otherwise reads as the app breaking, and a rider who stops to
 *   check has lost more than a notification would have cost.
 * - Removed from the group (403) → a different fact, and pretending it was a normal end would be
 *   dishonest about what happened.
 *
 * Pure, so the wording is testable without a composition — and so the ride-still-recording case
 * cannot quietly regress into the generic one.
 */
fun groupEndNoticeText(notice: GroupEndNotice, strings: AppStrings): String = when {
    notice.reason == GroupEndReason.REMOVED -> strings.groupRemoved
    notice.rideStillRecording -> strings.groupEndedRideContinues
    else -> strings.groupEnded
}
