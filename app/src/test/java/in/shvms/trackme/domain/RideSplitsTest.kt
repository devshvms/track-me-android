package `in`.shvms.trackme.domain

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Splits are only useful if they are comparable to each other, which makes boundary handling the
 * whole game: a split boundary almost never lands on a recorded sample, and rounding each leg to
 * one side pushes every later boundary further out of place.
 *
 * These drive the computation through an injected distance function so a synthetic ride can state
 * its own geometry exactly, rather than depending on real coordinates.
 */
class RideSplitsTest {

    /** Points a fixed number of seconds apart; distance comes from [legs], one per gap. */
    private fun ride(legs: List<Double>, secondsPerLeg: Long = 300L, paused: Set<Int> = emptySet()) =
        List(legs.size + 1) { i ->
            GPSPointEntity(
                id = i.toLong(),
                rideId = 1L,
                latitude = 0.0,
                longitude = 0.0,
                altitude = 0.0,
                accuracy = 5f,
                speed = 0f,
                timestamp = i * secondsPerLeg * 1000L,
                isPaused = i in paused,
            )
        }

    private fun legsOf(legs: List<Double>): (GPSPointEntity, GPSPointEntity) -> Double =
        { a, b -> legs[(b.id - 1).toInt()] }

    @Test
    fun exactKilometreLegsProduceExactSplits() {
        val legs = listOf(1000.0, 1000.0, 1000.0)
        val splits = rideSplits(ride(legs), imperial = false, distanceBetween = legsOf(legs))

        assertEquals(3, splits.size)
        assertTrue("no partial expected", splits.none { it.isPartial })
        splits.forEach { assertEquals(1000.0, it.distanceMeters, 0.001) }
        // 1000 m in 300 s each.
        splits.forEach { assertEquals(300_000L, it.movingMillis) }
    }

    @Test
    fun aLegStraddlingABoundaryIsDividedInProportion() {
        // One 2000 m leg over 600 s covers two kilometres. If the leg were assigned whole, the
        // first split would show 2000 m and the second would never appear.
        val legs = listOf(2000.0)
        val splits = rideSplits(ride(legs, secondsPerLeg = 600L), imperial = false, distanceBetween = legsOf(legs))

        assertEquals(2, splits.size)
        assertEquals(1000.0, splits[0].distanceMeters, 0.001)
        assertEquals(300_000L, splits[0].movingMillis)
        assertEquals(1000.0, splits[1].distanceMeters, 0.001)
        assertEquals(300_000L, splits[1].movingMillis)
    }

    @Test
    fun boundariesDoNotDriftAcrossManySplits() {
        // The failure this guards: rounding each leg to one side of a boundary accumulates, and by
        // the tenth kilometre the split is measured over noticeably more or less than a kilometre.
        val legs = List(70) { 143.0 } // 10.01 km in awkward, non-dividing legs
        val splits = rideSplits(ride(legs, secondsPerLeg = 43L), imperial = false, distanceBetween = legsOf(legs))

        val full = splits.filter { !it.isPartial }
        assertEquals(10, full.size)
        full.forEach { assertEquals("split ${it.index} drifted", 1000.0, it.distanceMeters, 0.001) }
        // Constant speed in, so every split must report the same time to within a millisecond of
        // integer truncation.
        val times = full.map { it.movingMillis }
        assertTrue("split times drifted: $times", times.max() - times.min() <= 2L)
    }

    @Test
    fun theRemainderIsReportedAndMarkedPartial() {
        val legs = listOf(1000.0, 400.0)
        val splits = rideSplits(ride(legs), imperial = false, distanceBetween = legsOf(legs))

        assertEquals(2, splits.size)
        assertTrue(!splits[0].isPartial)
        assertTrue("the tail must be marked", splits[1].isPartial)
        assertEquals(400.0, splits[1].distanceMeters, 0.001)
    }

    @Test
    fun aRoundingTailIsNotASplit() {
        // Two metres left over is float noise, not a split. Reporting it would put an absurd pace
        // at the bottom of the table.
        val legs = listOf(1000.0, 2.0)
        val splits = rideSplits(ride(legs), imperial = false, distanceBetween = legsOf(legs))
        assertEquals(1, splits.size)
    }

    @Test
    fun pausedLegsContributeNeitherDistanceNorTime() {
        // A coffee stop inside a kilometre must not make that kilometre look slow.
        val legs = listOf(500.0, 500.0, 500.0)
        val moving = rideSplits(ride(legs), imperial = false, distanceBetween = legsOf(legs))
        val withPause = rideSplits(
            ride(legs, paused = setOf(2)),
            imperial = false,
            distanceBetween = legsOf(legs),
        )

        assertEquals(300_000L * 2, moving[0].movingMillis)
        // With leg 2 paused, the first kilometre is made of legs 1 and 3 only.
        assertEquals(1000.0, withPause[0].distanceMeters, 0.001)
        assertEquals(300_000L * 2, withPause[0].movingMillis)
    }

    @Test
    fun imperialSplitsAreMiles() {
        val legs = listOf(1609.344)
        val splits = rideSplits(ride(legs), imperial = true, distanceBetween = legsOf(legs))
        assertEquals(1, splits.size)
        assertEquals(1609.344, splits[0].distanceMeters, 0.01)
        assertTrue(!splits[0].isPartial)
    }

    @Test
    fun tooFewPointsProduceNoSplits() {
        assertEquals(emptyList<RideSplit>(), rideSplits(emptyList(), imperial = false))
        assertEquals(emptyList<RideSplit>(), rideSplits(ride(emptyList()), imperial = false))
    }

    @Test
    fun fastestIgnoresThePartial() {
        // A 200 m remainder run flat out is not the same achievement as a fast kilometre.
        val splits = listOf(
            RideSplit(1, 1000.0, 300_000L, isPartial = false),
            RideSplit(2, 1000.0, 280_000L, isPartial = false),
            RideSplit(3, 200.0, 20_000L, isPartial = true),
        )
        assertEquals(2, fastestSplit(splits)?.index)
    }

    @Test
    fun splitsWithNoMovingTimeDoNotWin() {
        val splits = listOf(
            RideSplit(1, 1000.0, 0L, isPartial = false),
            RideSplit(2, 1000.0, 300_000L, isPartial = false),
        )
        assertEquals(2, fastestSplit(splits)?.index)
        assertEquals(0.0, splits[0].averageSpeedMps, 0.0001)
    }
}
