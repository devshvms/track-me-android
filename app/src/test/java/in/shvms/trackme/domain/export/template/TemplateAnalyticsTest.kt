package `in`.shvms.trackme.domain.export.template

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.domain.RideSplit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SCOPE_1.8.9 §6.1, §6.3, §12 R4 — draw what the data honestly shows, and nothing when it shows nothing. */
class TemplateAnalyticsTest {

    private fun point(
        index: Int,
        speed: Float = 3f,
        altitude: Double = 0.0,
        timestamp: Long = index * 10_000L,
        paused: Boolean = false,
    ) = GPSPointEntity(
        id = index.toLong(),
        rideId = 1L,
        latitude = 12.9 + index * 0.0001,
        longitude = 77.6,
        altitude = altitude,
        accuracy = 5f,
        speed = speed,
        timestamp = timestamp,
        isPaused = paused,
    )

    private val hundredMetreLegs: (GPSPointEntity, GPSPointEntity) -> Double = { _, _ -> 100.0 }

    // --- Pace along the line ---

    @Test
    fun `a ride held at one pace is drawn flat rather than as a gradient over noise`() {
        val points = List(50) { point(it, speed = 4f + (it % 2) * 0.1f) }
        assertTrue(paceIntensities(points).all { it == FLAT_PACE_INTENSITY })
    }

    @Test
    fun `a steady build runs from the slow end of the gradient to the fast end`() {
        val intensities = paceIntensities(List(60) { point(it, speed = 2f + it * 0.1f) })
        assertEquals(0f, intensities.first(), 0.05f)
        assertEquals(1f, intensities.last(), 0.05f)
        assertTrue(intensities.zipWithNext().all { (a, b) -> b >= a - 1e-4f })
    }

    @Test
    fun `one GPS spike does not squash the real ride into the bottom of the scale`() {
        val points = List(60) { point(it, speed = if (it == 30) 25f else 2f + (it % 10) * 0.2f) }
        assertTrue(paceIntensities(points).count { it > 0.5f } > 10)
    }

    @Test
    fun `paused samples sit at the slow end`() {
        val points = List(40) { point(it, speed = 2f + it * 0.1f, paused = it in 18..21) }
        assertEquals(0f, paceIntensities(points)[20], 0.05f)
    }

    // --- Elevation band ---

    @Test
    fun `no band without ten usable altitudes`() {
        assertNull(elevationProfile(List(9) { point(it, altitude = it.toDouble()) }, 5.0, distanceBetween = hundredMetreLegs))
    }

    @Test
    fun `no band when the device never reported an altitude`() {
        assertNull(elevationProfile(List(40) { point(it) }, 0.0, distanceBetween = hundredMetreLegs))
    }

    @Test
    fun `no band beside a gain the app itself declined to compute`() {
        assertNull(elevationProfile(List(40) { point(it, altitude = 900.0 + it) }, null, distanceBetween = hundredMetreLegs))
    }

    @Test
    fun `a climb rises across the band`() {
        val profile = elevationProfile(List(40) { point(it, altitude = 900.0 + it * 5) }, 195.0, distanceBetween = hundredMetreLegs)
        assertNotNull(profile)
        assertTrue(profile!!.heights.first() < 0.15f)
        assertTrue(profile.heights.last() > 0.85f)
        assertEquals(195.0, profile.gainMeters, 0.0)
    }

    @Test
    fun `a flat ride looks flat, not like a mountain range`() {
        val profile = elevationProfile(List(40) { point(it, altitude = 910.0 + (it % 3)) }, 2.0, distanceBetween = hundredMetreLegs)
        assertTrue(profile!!.heights.all { it < 0.2f })
    }

    // --- Split bars ---

    private fun split(index: Int, speedMps: Double, partial: Boolean = false) =
        RideSplit(index, if (partial) 400.0 else 1000.0, ((if (partial) 400.0 else 1000.0) / speedMps * 1000).toLong(), partial)

    @Test
    fun `the fastest full split is flagged and a quick partial is not a contender`() {
        val bars = splitBars(listOf(split(1, 3.0), split(2, 5.0), split(3, 4.0), split(4, 9.0, partial = true)))
        assertEquals(listOf(false, true, false, false), bars.map { it.isFastest })
        assertTrue(bars.last().isPartial)
    }

    @Test
    fun `taller is faster and no bar falls below thirty percent`() {
        val bars = splitBars(listOf(split(1, 3.0), split(2, 5.0), split(3, 4.0)))
        assertEquals(0.3f, bars[0].heightFraction, 1e-4f)
        assertEquals(1.0f, bars[1].heightFraction, 1e-4f)
        assertTrue(bars[2].heightFraction in 0.3f..1.0f)
    }

    @Test
    fun `no full split means no chart`() {
        assertTrue(splitBars(listOf(split(1, 4.0, partial = true))).isEmpty())
    }

    // --- Fastest segment on the line ---

    /** Three kilometres of 100 m legs; the second kilometre is run in 20 s legs, the others in 36 s. */
    private fun threeKilometres(): List<GPSPointEntity> {
        var time = 0L
        return List(31) { index ->
            if (index > 0) time += if (index in 11..20) 20_000L else 36_000L
            point(index, timestamp = time)
        }
    }

    @Test
    fun `the gold segment is the kilometre the splits call fastest`() {
        val points = threeKilometres()
        val segment = fastestSplitSegment(points, imperial = false, drawn = points, distanceBetween = hundredMetreLegs)!!
        val latitudes = segment.map { it.latitude }
        assertTrue(latitudes.first() >= points[9].latitude - 1e-9)
        assertTrue(latitudes.last() <= points[20].latitude + 1e-9)
        assertTrue(segment.size >= 11)
    }

    @Test
    fun `a fastest kilometre hidden inside the trimmed end is not drawn`() {
        val points = threeKilometres()
        assertNull(fastestSplitSegment(points, imperial = false, drawn = points.subList(21, 31), distanceBetween = hundredMetreLegs))
    }

    @Test
    fun `a ride shorter than a split has no fastest segment`() {
        val points = threeKilometres().take(8)
        assertNull(fastestSplitSegment(points, imperial = false, drawn = points, distanceBetween = hundredMetreLegs))
        assertFalse(splitBars(emptyList()).isNotEmpty())
    }
}
