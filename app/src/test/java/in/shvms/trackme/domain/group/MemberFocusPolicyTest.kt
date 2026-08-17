package `in`.shvms.trackme.domain.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SCOPE_1.7.3 §4 — **tap a roster row, open Home focused on that member.**
 *
 * The rule half is small. The wiring half is where this can go wrong, and both of its failure
 * modes are silent:
 *
 * 1. The focus is not cleared after use, so returning to Home an hour later snaps the camera to
 *    someone the rider has long since stopped caring about.
 * 2. The focus does not release follow-me, so the member is shown for a fraction of a second and
 *    then the next GPS fix drags the camera home — which looks like the tap did nothing.
 */
class MemberFocusPolicyTest {

    // --- The rule ------------------------------------------------------------------------------

    @Test
    fun `a member with a position is shown on the map`() {
        val outcome = MemberFocusPolicy.onRowTapped("uid-a", 12.9716, 77.5946)
        assertEquals(
            MemberFocusPolicy.Outcome.ShowOnMap(
                MemberFocusPolicy.Focus("uid-a", 12.9716, 77.5946),
            ),
            outcome,
        )
    }

    @Test
    fun `a member with no position explains itself instead of going inert`() {
        // Q4.2: "the row stays tappable but says why, rather than being inert." A dead row is
        // indistinguishable from a broken one.
        assertEquals(
            MemberFocusPolicy.Outcome.ExplainNoPosition,
            MemberFocusPolicy.onRowTapped("uid-b", null, null),
        )
    }

    @Test
    fun `half a coordinate is not a position`() {
        // Defensive, but the relay is a wire format and a partially-decoded position would put the
        // camera in the Gulf of Guinea rather than fail.
        assertEquals(
            MemberFocusPolicy.Outcome.ExplainNoPosition,
            MemberFocusPolicy.onRowTapped("uid-c", 12.9716, null),
        )
        assertEquals(
            MemberFocusPolicy.Outcome.ExplainNoPosition,
            MemberFocusPolicy.onRowTapped("uid-c", null, 77.5946),
        )
    }

    @Test
    fun `a stale position is still worth showing`() {
        // §2.3 (revised): the action survives staleness and keeps routing to an explicitly
        // labelled last known point. A rider searching for someone who stopped needs it most at
        // exactly the moment it went stale — the age on the row says how far to trust it.
        val outcome = MemberFocusPolicy.onRowTapped("uid-d", 51.5072, -0.1276)
        assertTrue(outcome is MemberFocusPolicy.Outcome.ShowOnMap)
    }

    @Test
    fun `zero-zero is a real coordinate and is not treated as absent`() {
        // Null Island is a legitimate lat/lng. Conflating "0.0" with "missing" is the classic way
        // this kind of check goes wrong.
        assertTrue(MemberFocusPolicy.onRowTapped("uid-e", 0.0, 0.0) is MemberFocusPolicy.Outcome.ShowOnMap)
    }

    @Test
    fun `only yourself has no marker to open`() {
        // §2.6 of 1.7.0: we never draw ourselves twice — the system blue dot is already there.
        assertFalse(MemberFocusPolicy.hasOpenableMarker(isSelf = true))
        assertTrue(MemberFocusPolicy.hasOpenableMarker(isSelf = false))
    }

    @Test
    fun `an absent focus is not applied`() {
        assertFalse(MemberFocusPolicy.shouldApply(null))
        assertTrue(MemberFocusPolicy.shouldApply(MemberFocusPolicy.Focus("uid", 1.0, 2.0)))
    }

    // --- The wiring ----------------------------------------------------------------------------

    @Test
    fun `the focus is consumed where it is applied`() {
        // §4: "Then it must clear, or returning to Home later would re-focus a member the rider has
        // moved on from." A one-shot that is never consumed is a camera that hijacks itself every
        // time Home is opened.
        val effect = bodyOf(homeScreenSource(), "LaunchedEffect(pendingMemberFocus)")
        assertTrue(
            "the focus effect must consume the pending focus, or it re-fires on every return to Home",
            effect.contains("app.consumePendingMemberFocus()"),
        )
    }

    @Test
    fun `focusing a member drops the camera into free-look`() {
        // §4's stated interaction with §1: "focusing a member is a camera move that must not be
        // immediately undone by follow-me. It should put the camera into free-look, exactly as a
        // manual pan would." Without this the tap visibly does nothing on a recording ride.
        val effect = bodyOf(homeScreenSource(), "LaunchedEffect(pendingMemberFocus)")
        assertTrue(
            "focusing a member must release follow via CameraFollowPolicy.onFocusedElsewhere()",
            effect.contains("CameraFollowPolicy.onFocusedElsewhere()"),
        )
    }

    @Test
    fun `every roster row is tappable, including one with no position`() {
        // Q4.2 again, from the other side. The card carries the click; gating the modifier on
        // lastKnownPosition would make the "says why" branch unreachable.
        val roster = communityScreenSource()
        assertTrue(
            "the roster card must be clickable",
            roster.contains(".clickable(onClick = onFocusMember)"),
        )
        assertTrue(
            "clearAndSetSemantics wipes the clickable's semantics, so the row must re-declare " +
                "onClick or it is un-actionable to TalkBack while working by touch",
            bodyOf(roster, "private fun RosterCard(").contains("onClick(label = strings.groupShowMemberOnMap)"),
        )
    }

    @Test
    fun `the tap routes through the policy rather than reimplementing it`() {
        val roster = communityScreenSource()
        assertTrue(
            "the roster tap must ask MemberFocusPolicy what to do",
            roster.contains("MemberFocusPolicy.onRowTapped("),
        )
        assertTrue(
            "the no-position branch must explain rather than fall through silently",
            roster.contains("groupMemberNoPositionYet"),
        )
    }

    /** Source with comments stripped — they name the calls the rules are about. */
    private fun homeScreenSource(): String = stripped("ui/home/HomeScreen.kt")

    private fun communityScreenSource(): String = stripped("ui/community/CommunityScreen.kt")

    private fun stripped(relative: String): String = read(relative)
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .replace(Regex("//.*"), "")

    private fun read(relative: String): String {
        var dir: File? = File("").absoluteFile
        val rel = "app/src/main/java/in/shvms/trackme/$relative"
        while (dir != null) {
            File(dir, rel).takeIf { it.exists() }?.let { return it.readText() }
            File(dir, rel.removePrefix("app/")).takeIf { it.exists() }?.let { return it.readText() }
            dir = dir.parentFile
        }
        throw AssertionError("$relative not found")
    }

    /** Brace-matched body of the named declaration. */
    private fun bodyOf(source: String, declaration: String): String {
        val start = source.indexOf(declaration)
        require(start >= 0) { "\"$declaration\" not found — did it get renamed?" }
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, i)
                }
            }
        }
        throw AssertionError("unbalanced braces in $declaration")
    }
}
