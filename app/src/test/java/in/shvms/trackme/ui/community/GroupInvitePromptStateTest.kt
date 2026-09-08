package `in`.shvms.trackme.ui.community

import `in`.shvms.trackme.data.remote.GroupSessionState
import `in`.shvms.trackme.data.remote.GroupSessionStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-289 — the empty-member state drives the invite prompt, so it is worth a test.
 *
 * `aloneInGroup` existed before this task but had no coverage at all, and it now decides whether a
 * user is shown the one action that makes their group useful. 42 people created a group and 2 sent
 * an invite; getting this predicate wrong in either direction is expensive. Wrong-false hides the
 * prompt from exactly the person who needs it, and wrong-true nags a group that is already full.
 */
class GroupInvitePromptStateTest {

    private fun member(uid: String, isLeader: Boolean, isSelf: Boolean) = RosterMember(
        uid = uid,
        displayName = uid,
        initials = uid.take(1).uppercase(),
        photoUrl = null,
        isLeader = isLeader,
        isSelf = isSelf,
        status = MemberStatus.JOINED_NOT_STARTED,
    )

    private fun state(isLeader: Boolean, roster: List<RosterMember>) = CommunityUiState(
        signedIn = true,
        session = GroupSessionState(
            status = GroupSessionStatus.PREPARING,
            groupId = "g1",
            joinCode = "ABC123",
            isLeader = isLeader,
        ),
        roster = roster,
    )

    @Test
    fun `leader alone sees the invite prompt`() {
        val s = state(isLeader = true, roster = listOf(member("leader", isLeader = true, isSelf = true)))
        assertTrue(s.aloneInGroup)
    }

    @Test
    fun `an empty roster still counts as alone`() {
        // The roster arrives asynchronously, so the leader can briefly see zero members. Showing
        // the prompt there is right: they are, in fact, the only one in the group.
        val s = state(isLeader = true, roster = emptyList())
        assertTrue(s.aloneInGroup)
    }

    @Test
    fun `the prompt disappears the moment someone joins`() {
        val s = state(
            isLeader = true,
            roster = listOf(
                member("leader", isLeader = true, isSelf = true),
                member("guest", isLeader = false, isSelf = false),
            ),
        )
        assertFalse(s.aloneInGroup)
    }

    @Test
    fun `a joiner waiting alone is not prompted to invite`() {
        // Only the leader owns the invite. A member who joined by code and happens to be looking at
        // a roster of one must not be told to invite people to someone else's group.
        val s = state(isLeader = false, roster = listOf(member("guest", isLeader = false, isSelf = true)))
        assertFalse(s.aloneInGroup)
    }

    @Test
    fun `being alone never lets the group start`() {
        // §8: "A group of one never enters LIVE; there is nothing to be co-present with." The
        // invite prompt is the answer to that state, so the two must not disagree.
        val alone = state(isLeader = true, roster = listOf(member("leader", isLeader = true, isSelf = true)))
        assertTrue(alone.aloneInGroup)
        assertFalse(alone.canStart)
    }
}
