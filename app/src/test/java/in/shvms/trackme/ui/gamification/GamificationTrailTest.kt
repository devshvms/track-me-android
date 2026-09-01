package `in`.shvms.trackme.ui.gamification

import `in`.shvms.trackme.domain.gamification.GamificationEngine
import `in`.shvms.trackme.domain.gamification.GamificationFacts
import `in`.shvms.trackme.domain.gamification.GamificationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TASK-276: the geometry that decides what the rider is told about their position. */
class GamificationTrailTest {

    private fun snapshotFor(minutes: Long, activities: Int = 1) =
        GamificationEngine.deriveSnapshot(
            GamificationFacts(
                lifetimeActivityCount = activities,
                lifetimeActiveDurationMillis = minutes * 60_000L,
            )
        )

    @Test
    fun `progress is measured within the level, not against the next threshold`() {
        // 257 minutes is level 2 (120) heading for level 3 (600): 137 of 480, not 257 of 600.
        val s = snapshotFor(257)
        assertEquals(1, GamificationTrail.levelIndexOf(s))
        assertEquals(137f / 480f, GamificationTrail.progressWithinLevel(s), 0.0001f)
    }

    @Test
    fun `the maximum level reads as complete`() {
        val s = snapshotFor(9_000)
        assertEquals(GamificationEngine.levels.lastIndex, GamificationTrail.levelIndexOf(s))
        assertEquals(1f, GamificationTrail.progressWithinLevel(s), 0.0001f)
    }

    @Test
    fun `a malformed denominator below the top reads as zero, not as finished`() {
        val malformed = GamificationSnapshot(
            currentLevelId = "level_2",
            currentLevelNameKey = "Moving",
            currentMinutes = 257,
            currentThresholdMinutes = 120,
            nextThresholdMinutes = 600,
            progressNumeratorMinutes = 137,
            progressDenominatorMinutes = 0,
            latestUnlockedMilestoneId = null,
            unlockedMilestoneIds = emptyList(),
            unlockedMilestoneCount = 0,
        )
        assertEquals(0f, GamificationTrail.progressWithinLevel(malformed), 0.0001f)
        assertEquals(
            0f,
            GamificationTrail.progressWithinLevel(malformed.copy(progressDenominatorMinutes = -1)),
            0.0001f,
        )
    }

    @Test
    fun `the marker sits between its level and the next, never beyond`() {
        val s = snapshotFor(257)
        val here = GamificationTrail.fractionForLevel(1)
        val next = GamificationTrail.fractionForLevel(2)
        val marker = GamificationTrail.markerFraction(s)
        assertTrue("marker $marker below level 2 at $here", marker > here)
        assertTrue("marker $marker beyond level 3 at $next", marker < next)
    }

    @Test
    fun `a brand new rider starts at the foot of the trail`() {
        assertEquals(0f, GamificationTrail.markerFraction(snapshotFor(0, activities = 0)), 0.0001f)
    }

    @Test
    fun `the maximum level puts the marker at the summit`() {
        assertEquals(1f, GamificationTrail.markerFraction(snapshotFor(12_000)), 0.0001f)
    }

    @Test
    fun `every level gets one node, tagged by state`() {
        val nodes = GamificationTrail.nodes(snapshotFor(257))
        assertEquals(GamificationEngine.levels.size, nodes.size)
        assertEquals(GamificationTrail.NodeState.PASSED, nodes[0].state)
        assertEquals(GamificationTrail.NodeState.CURRENT, nodes[1].state)
        assertEquals(GamificationTrail.NodeState.AHEAD, nodes[2].state)
        assertEquals(GamificationEngine.levels.map { it.id }, nodes.map { it.levelId })
    }

    @Test
    fun `nodes stay inside the drawing box`() {
        // A waypoint outside the box would clip its own number, which is how the radial version lost
        // half of every lock icon.
        GamificationTrail.nodes(snapshotFor(257)).forEach { node ->
            assertTrue("x=${node.position.x}", node.position.x in 0f..GamificationTrail.WIDTH)
            assertTrue("y=${node.position.y}", node.position.y in 0f..GamificationTrail.HEIGHT)
        }
    }

    @Test
    fun `the card is placed on whichever side the path is not using`() {
        GamificationTrail.nodes(snapshotFor(257)).forEach { node ->
            assertEquals(node.position.x < GamificationTrail.WIDTH / 2f, node.cardOnRight)
        }
    }

    @Test
    fun `the trail climbs — later levels are higher up the screen`() {
        val nodes = GamificationTrail.nodes(snapshotFor(257))
        nodes.zipWithNext { lower, higher ->
            assertTrue(
                "level ${higher.levelIndex} at y=${higher.position.y} is not above ${lower.position.y}",
                higher.position.y < lower.position.y,
            )
        }
    }
}
