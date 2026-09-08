package `in`.shvms.trackme.domain.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SCOPE_1.8.7 §6.1.4 scenario 22 — the trust sibling of §6.1.1 #6. */
class GroupPresenceNoticeTest {

    @Test
    fun `a live group after the ride ended is worth saying`() {
        assertTrue(
            GroupPresenceNotice.shouldNotify(
                activeGroupId = "g1",
                isGroupLive = true,
                isRideActive = false,
                alreadyNoticedGroupIds = emptySet(),
            )
        )
    }

    @Test
    fun `no group means nothing to say`() {
        assertFalse(
            GroupPresenceNotice.shouldNotify(
                activeGroupId = null,
                isGroupLive = true,
                isRideActive = false,
                alreadyNoticedGroupIds = emptySet(),
            )
        )
    }

    @Test
    fun `a blank group id is not a group`() {
        assertFalse(
            GroupPresenceNotice.shouldNotify(
                activeGroupId = "   ",
                isGroupLive = true,
                isRideActive = false,
                alreadyNoticedGroupIds = emptySet(),
            )
        )
    }

    /** An expired group shares nothing, so warning about it raises an alarm about a passed risk. */
    @Test
    fun `an ended group is not a disclosure`() {
        assertFalse(
            GroupPresenceNotice.shouldNotify(
                activeGroupId = "g1",
                isGroupLive = false,
                isRideActive = false,
                alreadyNoticedGroupIds = emptySet(),
            )
        )
    }

    /** During a ride, group presence is the point, and the ongoing notification already says so. */
    @Test
    fun `it stays quiet while a ride is running`() {
        assertFalse(
            GroupPresenceNotice.shouldNotify(
                activeGroupId = "g1",
                isGroupLive = true,
                isRideActive = true,
                alreadyNoticedGroupIds = emptySet(),
            )
        )
    }

    /**
     * Once per group, ever. A rider told about group X who chose to stay has answered; asking again
     * after their next ride is the app arguing with a decision it prompted.
     */
    @Test
    fun `it asks about a given group only once`() {
        assertFalse(
            GroupPresenceNotice.shouldNotify(
                activeGroupId = "g1",
                isGroupLive = true,
                isRideActive = false,
                alreadyNoticedGroupIds = setOf("g1"),
            )
        )
    }

    @Test
    fun `a different group is a different disclosure`() {
        assertTrue(
            GroupPresenceNotice.shouldNotify(
                activeGroupId = "g2",
                isGroupLive = true,
                isRideActive = false,
                alreadyNoticedGroupIds = setOf("g1"),
            )
        )
    }
}
