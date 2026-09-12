package `in`.shvms.trackme.domain.export.template

import `in`.shvms.trackme.data.local.entity.RideSource
import `in`.shvms.trackme.domain.stats.RevealKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** SCOPE_1.8.9 §6.4 and §11 gate 4 — The Award has to be able to say no. */
class AwardEligibilityTest {

    private fun facts(reveal: EarnedReveal?, distance: Double = 41_700.0, active: Long? = 8_000_000L, source: String = RideSource.RECORDED) =
        AwardEligibility.facts(reveal, source, distance, active)

    @Test
    fun `an ordinary ride and a ride never evaluated earn nothing`() {
        assertNull(facts(EarnedReveal(RevealKind.DEFAULT, null, null)))
        assertNull(facts(null))
    }

    @Test
    fun `an imported ride is never eligible whatever its row says`() {
        assertNull(facts(EarnedReveal(RevealKind.FIRST_RIDE, null, null), source = RideSource.IMPORTED))
    }

    @Test
    fun `a distance PR draws the old record as the covered part of the ring`() {
        val award = facts(EarnedReveal(RevealKind.DISTANCE_PR, 38_200.0, null))!!
        assertEquals(38_200.0, award.previousBest!!, 0.0)
        assertEquals((38_200.0 / 41_700.0).toFloat(), award.previousFraction, 1e-6f)
    }

    @Test
    fun `a PR the stored ride no longer supports is not rendered`() {
        // Chosen at 38.5 km live against a 38.2 km record; post-processing stored 38.0 km.
        assertNull(facts(EarnedReveal(RevealKind.DISTANCE_PR, 38_200.0, null), distance = 38_000.0))
        assertNull(facts(EarnedReveal(RevealKind.DISTANCE_PR, 38_200.0, null), distance = 38_200.0))
    }

    @Test
    fun `a duration PR is checked against stored active time`() {
        assertNotNull(facts(EarnedReveal(RevealKind.DURATION_PR, 7_000_000.0, null)))
        assertNull(facts(EarnedReveal(RevealKind.DURATION_PR, 9_000_000.0, null)))
        assertNull(facts(EarnedReveal(RevealKind.DURATION_PR, 7_000_000.0, null), active = null))
    }

    @Test
    fun `a first ride fills the ring and a milestone needs its count`() {
        assertEquals(1f, facts(EarnedReveal(RevealKind.FIRST_RIDE, null, null))!!.previousFraction, 0f)
        assertEquals(100, facts(EarnedReveal(RevealKind.MILESTONE, null, 100))!!.milestoneCount)
        assertNull(facts(EarnedReveal(RevealKind.MILESTONE, null, null)))
    }

    @Test
    fun `persisted columns parse, and an unknown kind from a newer build is treated as none`() {
        assertEquals(EarnedReveal(RevealKind.DISTANCE_PR, 1.0, null), AwardEligibility.parse("DISTANCE_PR", 1.0, null))
        assertNull(AwardEligibility.parse("GOLD_STAR", 1.0, null))
        assertNull(AwardEligibility.parse(null, null, null))
    }
}
