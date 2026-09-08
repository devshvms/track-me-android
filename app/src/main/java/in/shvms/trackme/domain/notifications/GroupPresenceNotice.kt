package `in`.shvms.trackme.domain.notifications

/**
 * SCOPE_1.8.7 §6.1.4 scenario 22 — *"Your ride ended, but you are still in a live group."*
 *
 * The trust sibling of §6.1.1 #6. A group ride keeps sharing the rider's position with the other
 * members for as long as the group is live, and stopping a ride does not leave the group. So a
 * rider who finishes, pockets the phone and goes to dinner can remain visible to people who are no
 * longer riding with them, having done nothing to cause it and seen nothing to say so.
 *
 * ### Class A, and why that is not a licence
 *
 * This is consequential — it is about location sharing the rider is not aware of — so §6.0's
 * proactive budget does not apply. That makes the dedupe rules here the only thing standing between
 * a trust notification and a nuisance, so they are strict: **once per group, ever**. A rider who
 * has been told they are still in group X and chose to stay has answered the question. Asking again
 * after their next ride is the app arguing with a decision it already prompted.
 *
 * ### Why it does not leave the group for them
 *
 * The same reason scenario 4 does not stop the ride. Staying in the group after your own ride ends
 * is a completely ordinary thing to do — the ride leader who finishes first, anyone waiting for the
 * back marker — and silently ending someone's group participation would break a social contract the
 * app cannot see. It tells, and offers one tap to leave.
 */
object GroupPresenceNotice {

    /**
     * Whether to tell the rider they are still in a live group.
     *
     * @param activeGroupId the group the rider is still a member of, or null if none.
     * @param isGroupLive whether that group is still broadcasting. An expired or ended group shares
     *   nothing, so saying so would be raising an alarm about a risk that has already passed.
     * @param isRideActive whether a ride is running. During a ride, group presence is the point,
     *   and the ongoing notification already states it — this is only about the gap afterwards.
     * @param alreadyNoticedGroupIds groups the rider has already been told about.
     */
    fun shouldNotify(
        activeGroupId: String?,
        isGroupLive: Boolean,
        isRideActive: Boolean,
        alreadyNoticedGroupIds: Set<String>,
    ): Boolean {
        val groupId = activeGroupId?.takeIf { it.isNotBlank() } ?: return false
        if (!isGroupLive) return false
        if (isRideActive) return false
        if (groupId in alreadyNoticedGroupIds) return false
        return true
    }
}
