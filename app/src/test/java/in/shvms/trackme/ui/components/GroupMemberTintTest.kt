package `in`.shvms.trackme.ui.components

import `in`.shvms.trackme.ui.home.components.deterministicMarkerTint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One colour per member, everywhere they appear — SCOPE_1.7.0 §3.3, §3.6.
 *
 * This exists because it was not true. The roster and the map marker each owned their own ramp, so
 * the same member rendered in two different colours: the roster taught you nothing about the map,
 * which is the entire job §3.3 gives the tint.
 */
class GroupMemberTintTest {

    @Test
    fun `the roster avatar and the map marker agree for the same member`() {
        // The regression that motivated this file. Two ramps, silently disagreeing.
        for (uid in listOf("uid-alice", "uid-bob", "uid-priya", "uid-ravi", "x", "🚴")) {
            assertEquals(
                "roster and marker disagree for $uid",
                GroupMemberTint.argbFor(uid),
                deterministicMarkerTint(uid),
            )
        }
    }

    @Test
    fun `a member keeps the same colour for the whole session`() {
        // §3.3 wants the tint to separate people at a glance, which only works if it is stable
        // across a reconnect, a restore, and every recomposition in between.
        val first = GroupMemberTint.argbFor("uid-alice")
        repeat(100) { assertEquals(first, GroupMemberTint.argbFor("uid-alice")) }
    }

    @Test
    fun `the compose colour matches the argb one`() {
        for (uid in listOf("uid-a", "uid-b", "uid-c")) {
            assertEquals(GroupMemberTint.argbFor(uid), GroupMemberTint.colorFor(uid).value.toLong().shr(32).toInt())
        }
    }

    @Test
    fun `different members generally get different colours`() {
        val tints = (1..40).map { GroupMemberTint.argbFor("uid-$it") }.toSet()
        assertTrue("the ramp collapsed", tints.size > 1)
    }

    @Test
    fun `no uid can index outside the ramp`() {
        // hashCode is signed, and a negative index would crash the map for some members and not
        // others — the worst kind of intermittent.
        for (uid in listOf("", "a", "-", "zzzzzzzzzzzzzzzz", "🚴‍♀️", "uid-with-a-very-long-identifier")) {
            val argb = GroupMemberTint.argbFor(uid)
            assertTrue("$uid produced a colour outside the ramp", GroupMemberTint.RAMP_ARGB.contains(argb))
        }
    }

    @Test
    fun `every ramp entry is fully opaque`() {
        // A translucent fill over a map would wash out to something unrecognisable, and two members
        // could end up looking identical over different terrain.
        for (argb in GroupMemberTint.RAMP_ARGB) {
            assertEquals("ramp entry is not opaque", 0xFF, (argb ushr 24) and 0xFF)
        }
    }
}
