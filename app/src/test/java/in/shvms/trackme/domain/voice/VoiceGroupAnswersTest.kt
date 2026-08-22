package `in`.shvms.trackme.domain.voice

import `in`.shvms.trackme.domain.group.RiderStatusCatalog
import `in`.shvms.trackme.domain.group.RiderStatusCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The §4.4 disclosure table, asserted row by row.
 *
 * These are the tests that stop a well-meaning change from making voice sound more certain than the
 * data is. The failure they guard against shipped once already as a *documented example* in the
 * architecture contract: a two-minute-old fix described as "500 meters ahead".
 */
class VoiceGroupAnswersTest {

    private fun member(name: String?, freshness: VoiceFreshness, key: String = name.orEmpty()) =
        VoiceGroupMemberFact(cacheKey = key, displayName = name, freshness = freshness)

    // ---- §4.4 disclosure table ----------------------------------------------------------------

    @Test
    fun `fresh fix with a vouchable heading is the only case that earns a direction`() {
        val d = VoiceGroupAnswers.discloseMember(
            freshness = VoiceFreshness.Now,
            distanceMeters = 500.0,
            direction = VoiceDirection.AHEAD,
            headingIsVouchable = true,
        )
        assertEquals(VoiceMemberDisclosure.DistanceAndDirection(500, VoiceDirection.AHEAD), d)
    }

    @Test
    fun `a fresh fix without a vouchable heading gives distance but no direction`() {
        // HeadingTail.shouldDraw is false when the member is stationary or auto-paused: a trail
        // behind a parked rider reads as movement that is not happening.
        val d = VoiceGroupAnswers.discloseMember(
            freshness = VoiceFreshness.Now,
            distanceMeters = 500.0,
            direction = VoiceDirection.AHEAD,
            headingIsVouchable = false,
        )
        assertTrue(d is VoiceMemberDisclosure.DistanceWithAge)
    }

    @Test
    fun `seconds and minutes old give distance with age, never a direction`() {
        listOf(VoiceFreshness.Seconds(40), VoiceFreshness.Minutes(2)).forEach { fresh ->
            val d = VoiceGroupAnswers.discloseMember(fresh, 500.0, VoiceDirection.AHEAD, headingIsVouchable = true)
            assertTrue("$fresh must not earn a direction", d is VoiceMemberDisclosure.DistanceWithAge)
            assertEquals(fresh, (d as VoiceMemberDisclosure.DistanceWithAge).freshness)
        }
    }

    @Test
    fun `the architecture contract's own example line is not implementable`() {
        // "Alice's last known location from 2 minutes ago was 500 meters ahead of you" — the
        // direction half of that sentence cannot be produced by any input.
        val d = VoiceGroupAnswers.discloseMember(
            freshness = VoiceFreshness.Minutes(2),
            distanceMeters = 500.0,
            direction = VoiceDirection.AHEAD,
            headingIsVouchable = true,
        )
        assertTrue(d !is VoiceMemberDisclosure.DistanceAndDirection)
    }

    @Test
    fun `hours old withholds the distance entirely`() {
        val d = VoiceGroupAnswers.discloseMember(
            VoiceFreshness.Hours(1), 500.0, VoiceDirection.AHEAD, headingIsVouchable = true
        )
        assertEquals(VoiceMemberDisclosure.AgeOnly(VoiceFreshness.Hours(1)), d)
    }

    @Test
    fun `unknown age never produces a number`() {
        // The sender rebooted; the age is unrecoverable. Speak presence, never a guessed age.
        val d = VoiceGroupAnswers.discloseMember(
            VoiceFreshness.Unknown, 500.0, VoiceDirection.AHEAD, headingIsVouchable = true
        )
        assertEquals(VoiceMemberDisclosure.PresenceOnly, d)
    }

    @Test
    fun `a member with no cached position discloses presence only`() {
        val d = VoiceGroupAnswers.discloseMember(VoiceFreshness.Now, null, null, headingIsVouchable = true)
        assertEquals(VoiceMemberDisclosure.PresenceOnly, d)
    }

    // ---- §4.3 rounding ------------------------------------------------------------------------

    @Test
    fun `under a kilometre rounds to fifty metres`() {
        assertEquals(500, VoiceGroupAnswers.roundDistanceMeters(487.0))
        assertEquals(450, VoiceGroupAnswers.roundDistanceMeters(462.0))
        assertEquals(0, VoiceGroupAnswers.roundDistanceMeters(12.0))
    }

    @Test
    fun `above a kilometre rounds to one decimal`() {
        assertEquals(6300, VoiceGroupAnswers.roundDistanceMeters(6_284.0))
        assertEquals(1000, VoiceGroupAnswers.roundDistanceMeters(1_004.0))
    }

    // ---- §4.6 name matching -------------------------------------------------------------------

    @Test
    fun `exact match wins and ignores case and diacritics`() {
        val alice = member("Alice", VoiceFreshness.Now)
        val m = VoiceGroupAnswers.matchName("alice", listOf(alice, member("Bob", VoiceFreshness.Now)))
        assertEquals(VoiceNameMatch.Matched(alice), m)

        val chloe = member("Chloé", VoiceFreshness.Now)
        assertEquals(VoiceNameMatch.Matched(chloe), VoiceGroupAnswers.matchName("chloe", listOf(chloe)))
    }

    @Test
    fun `a unique prefix matches`() {
        val alice = member("Alice", VoiceFreshness.Now)
        assertEquals(
            VoiceNameMatch.Matched(alice),
            VoiceGroupAnswers.matchName("Ali", listOf(alice, member("Bob", VoiceFreshness.Now))),
        )
    }

    @Test
    fun `two candidates ask rather than guess`() {
        val alice = member("Alice", VoiceFreshness.Now)
        val alex = member("Alex", VoiceFreshness.Now)
        val m = VoiceGroupAnswers.matchName("Al", listOf(alice, alex))
        assertTrue(m is VoiceNameMatch.Ambiguous)
        assertEquals(2, (m as VoiceNameMatch.Ambiguous).candidates.size)
    }

    @Test
    fun `duplicate display names are ambiguous, never a silent pick`() {
        val a1 = member("Alex", VoiceFreshness.Now, key = "k1")
        val a2 = member("Alex", VoiceFreshness.Now, key = "k2")
        assertTrue(VoiceGroupAnswers.matchName("Alex", listOf(a1, a2)) is VoiceNameMatch.Ambiguous)
    }

    @Test
    fun `an unknown name does not match anyone`() {
        assertEquals(
            VoiceNameMatch.NoMatch,
            VoiceGroupAnswers.matchName("Zara", listOf(member("Alice", VoiceFreshness.Now))),
        )
        assertEquals(VoiceNameMatch.NoMatch, VoiceGroupAnswers.matchName("   ", listOf(member("Alice", VoiceFreshness.Now))))
    }

    @Test
    fun `members with no display name are never matched`() {
        assertEquals(
            VoiceNameMatch.NoMatch,
            VoiceGroupAnswers.matchName("alice", listOf(member(null, VoiceFreshness.Now, key = "k"))),
        )
    }

    // ---- §4.5 roster --------------------------------------------------------------------------

    private fun available(vararg m: VoiceGroupMemberFact) =
        VoiceGroupCacheResult.Available(VoiceGroupConnection.CURRENT, m.toList())

    @Test
    fun `alerts come only from a declared severity-1 status`() {
        val bob = member("Bob", VoiceFreshness.Minutes(3))
        val alice = member("Alice", VoiceFreshness.Now)
        val needHelp = RiderStatusCodec.parse(RiderStatusCatalog.NEED_HELP)
        val breakStatus = RiderStatusCodec.parse(RiderStatusCatalog.SHORT_BREAK)

        val answer = VoiceGroupAnswers.roster(available(alice, bob)) { fact ->
            if (fact.displayName == "Bob") needHelp else breakStatus
        }
        assertEquals(listOf(bob), answer.alerts)
        assertEquals(2, answer.memberCount)
    }

    @Test
    fun `movement never becomes an alert and never becomes reassurance`() {
        // Four moving riders with no declared status must yield zero alerts — the roster reports
        // what people said, not what their dots did.
        val movers = (1..4).map { member("R$it", VoiceFreshness.Now, key = "k$it") }
        val answer = VoiceGroupAnswers.roster(available(*movers.toTypedArray())) { null }
        assertTrue(answer.alerts.isEmpty())
        assertEquals(4, answer.recentlyHeardCount)
        assertTrue(answer.notHeardFrom.isEmpty())
    }

    @Test
    fun `stale and age-unknown members are reported as not heard from`() {
        val fresh = member("Alice", VoiceFreshness.Now)
        val old = member("Bob", VoiceFreshness.Hours(1))
        val rebooted = member("Cara", VoiceFreshness.Unknown)
        val answer = VoiceGroupAnswers.roster(available(fresh, old, rebooted)) { null }
        assertEquals(1, answer.recentlyHeardCount)
        assertEquals(listOf(old, rebooted), answer.notHeardFrom)
    }

    @Test
    fun `a degraded connection is carried into the answer so it can be said aloud`() {
        val answer = VoiceGroupAnswers.roster(
            VoiceGroupCacheResult.Available(VoiceGroupConnection.DEGRADED, listOf(member("A", VoiceFreshness.Now)))
        ) { null }
        assertEquals(VoiceGroupConnection.DEGRADED, answer.connection)
    }
}
