package `in`.shvms.trackme.ui.community

import `in`.shvms.trackme.domain.group.RiderStatusCatalog
import `in`.shvms.trackme.domain.group.RiderStatusCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The roster split that hid a shipped bug — SCOPE_1.7.2 §3.4, amendment **A31**.
 *
 * Device testing found "Need help" could not be withdrawn from anywhere in the app. Two defects
 * compounded: the status chip was not tappable and the "set status" affordance was an `else` to it,
 * so it disappeared once anything was set — and a severity-1 status moves you into the **attention
 * section**, which was rendered by a different call site that passed no status callback at all.
 *
 * 537 tests were green throughout, because nothing asserted the one property that mattered: **your
 * own row can land in either partition**, so both must treat it identically. That is what this
 * file pins. It is a cheap test for a defect that made the release's most important status a
 * one-way door.
 */
class RosterPartitionTest {

    private fun member(
        uid: String,
        isSelf: Boolean = false,
        code: String? = null,
    ) = RosterMember(
        uid = uid,
        displayName = uid,
        initials = uid.take(2).uppercase(),
        photoUrl = null,
        isLeader = false,
        isSelf = isSelf,
        status = MemberStatus.RIDING,
        riderStatus = code?.let { RiderStatusCodec.parse(it) },
    )

    private fun state(vararg members: RosterMember) = CommunityUiState(roster = members.toList())

    @Test
    fun `a severity-1 member is pinned to the attention section`() {
        val s = state(
            member("ravi", code = RiderStatusCatalog.NEED_HELP),
            member("priya", code = RiderStatusCatalog.TIRED),
        )
        assertEquals(listOf("ravi"), s.needsAttention.map { it.uid })
        assertEquals(listOf("priya"), s.everyoneElse.map { it.uid })
    }

    @Test
    fun `YOUR OWN severity-1 status puts YOU in the attention section`() {
        // The fact the bug turned on. Self is not exempt from the pin, so the attention section is
        // a place your own row genuinely appears — and therefore a place it must stay editable.
        val s = state(member("me", isSelf = true, code = RiderStatusCatalog.NEED_HELP))
        assertEquals(1, s.needsAttention.size)
        assertTrue("self must be pinned like anyone else", s.needsAttention.single().isSelf)
        assertTrue(s.everyoneElse.isEmpty())
    }

    @Test
    fun `self moves between partitions purely on severity`() {
        // Both partitions must render self identically, because which one holds you is decided by
        // a status you can change at any moment. Anything wired into only one of them is a trap.
        val alert = state(member("me", isSelf = true, code = RiderStatusCatalog.NEED_HELP))
        val caution = state(member("me", isSelf = true, code = RiderStatusCatalog.VEHICLE_ISSUE))
        val none = state(member("me", isSelf = true))

        assertTrue(alert.needsAttention.single().isSelf)
        assertTrue(caution.everyoneElse.single().isSelf)
        assertTrue(none.everyoneElse.single().isSelf)
    }

    @Test
    fun `caution and info never pin`() {
        val s = state(
            member("a", code = RiderStatusCatalog.VEHICLE_ISSUE),
            member("b", code = RiderStatusCatalog.ENGINE_HEAT),
            member("c", code = RiderStatusCatalog.TIRED),
            member("d"),
        )
        assertTrue("only severity 1 pins", s.needsAttention.isEmpty())
        assertEquals(4, s.everyoneElse.size)
    }

    @Test
    fun `the partitions are a complete split, with nobody duplicated or dropped`() {
        // §3.4: the attention section is absent entirely when empty, and no member may appear twice
        // — a duplicated row would show one person's alert in two places at once.
        val s = state(
            member("me", isSelf = true, code = RiderStatusCatalog.CRASHED),
            member("ravi", code = RiderStatusCatalog.NEED_HELP),
            member("priya", code = RiderStatusCatalog.TIRED),
            member("sam"),
        )
        val all = s.needsAttention + s.everyoneElse
        assertEquals(s.roster.size, all.size)
        assertEquals(s.roster.map { it.uid }.toSet(), all.map { it.uid }.toSet())
        assertEquals(all.size, all.map { it.uid }.distinct().size)
    }

    @Test
    fun `an unknown severity-1 code from a newer client still pins`() {
        // §4.2's fallback has to survive all the way to the layout: severity is readable from the
        // code even when the message is not, so an unrecognised alert must not sink into the list.
        val s = state(member("ravi", code = "1GZZ"))
        assertEquals(listOf("ravi"), s.needsAttention.map { it.uid })
    }

    @Test
    fun `a malformed code does not pin anyone`() {
        val s = state(member("ravi", code = "garbage"))
        assertTrue(s.needsAttention.isEmpty())
        assertEquals(1, s.everyoneElse.size)
    }
}
