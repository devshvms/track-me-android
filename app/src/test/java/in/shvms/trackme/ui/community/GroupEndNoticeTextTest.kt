package `in`.shvms.trackme.ui.community

import `in`.shvms.trackme.data.remote.GroupEndNotice
import `in`.shvms.trackme.data.remote.GroupEndReason
import `in`.shvms.trackme.ui.localization.AppStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SCOPE_1.7.0 §8 — the "clear notice" a member gets when a group stops without them doing
 * anything.
 *
 * This existed as a string in seven locales with **no call site** until a member watched their map
 * go blank mid-ride with no explanation. The wording is pure so it is testable, and so the
 * ride-still-recording case cannot quietly regress into the generic one.
 */
class GroupEndNoticeTextTest {

    private val strings = AppStrings()

    private fun notice(reason: GroupEndReason, riding: Boolean = false) =
        GroupEndNotice(reason = reason, rideStillRecording = riding)

    @Test
    fun `a leader ending the group says so plainly`() {
        assertEquals(strings.groupEnded, groupEndNoticeText(notice(GroupEndReason.ENDED), strings))
    }

    @Test
    fun `an expired group reads the same as an ended one`() {
        // §8 treats both identically from the member's side, and rightly: there was a visible
        // countdown, so "expired" is not new information worth different words.
        assertEquals(
            groupEndNoticeText(notice(GroupEndReason.ENDED), strings),
            groupEndNoticeText(notice(GroupEndReason.EXPIRED), strings),
        )
    }

    @Test
    fun `ending mid-ride says the ride is still recording`() {
        // §8: "Session TTL expires mid-ride → Group Mode off; RIDE KEEPS RECORDING → clear notice
        // that the group ended and the ride continues."
        //
        // A map going blank while you are recording otherwise reads as the app breaking, and a
        // rider who stops to check has lost more than a sentence would have cost.
        val text = groupEndNoticeText(notice(GroupEndReason.ENDED, riding = true), strings)
        assertEquals(strings.groupEndedRideContinues, text)
        assertNotEquals(strings.groupEnded, text)
        assertTrue("the notice does not mention the ride", text.contains("ride", ignoreCase = true))
    }

    @Test
    fun `being removed is not dressed up as a normal ending`() {
        // A 403 means this member is no longer in the group (§5.2). Saying "the group has ended"
        // would be untrue — it is still running, without them.
        val text = groupEndNoticeText(notice(GroupEndReason.REMOVED), strings)
        assertEquals(strings.groupRemoved, text)
        assertNotEquals(strings.groupEnded, text)
    }

    @Test
    fun `removal takes precedence over the ride state`() {
        // Riding or not, "you are no longer in this group" is the fact that matters.
        assertEquals(
            strings.groupRemoved,
            groupEndNoticeText(notice(GroupEndReason.REMOVED, riding = true), strings),
        )
    }

    @Test
    fun `every reason produces a non-empty, non-placeholder message`() {
        // The failure this whole class exists to prevent: a state with no words attached.
        for (reason in GroupEndReason.entries) {
            for (riding in listOf(true, false)) {
                val text = groupEndNoticeText(notice(reason, riding), strings)
                assertTrue("$reason/$riding produced no message", text.isNotBlank())
                assertTrue("$reason/$riding looks like a key, not a sentence", text.length > 10)
            }
        }
    }
}
