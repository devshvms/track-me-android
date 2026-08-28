package `in`.shvms.trackme.ui.onboarding

import `in`.shvms.trackme.data.local.HOME_DASHBOARD_METADATA_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-248. The sample ride is the first ride most riders ever open, and it was the one ride that
 * never got dashboard metadata: seeded after the only backfill sweep had already drained, so its
 * grid read "Unknown" for duration and dropped the elevation cell entirely.
 *
 * These assert the seeded row is complete, because nothing downstream will repair it — that is the
 * whole point of the defect.
 */
class SampleRideMetadataTest {

    private val fixture = OnboardingDemoFixture.create(startTimeMillis = 1_000L, title = "Sample ride")
    private val seeded = sampleRideWithMetadata(fixture)

    @Test
    fun `the seeded sample is at the current metadata version`() {
        // The version gate is what made the duration read "Unknown": the display refuses a duration
        // from a row it cannot vouch for, and a raw insert left this at 0 forever.
        assertEquals(HOME_DASHBOARD_METADATA_VERSION, seeded.dashboardMetadataVersion)
    }

    @Test
    fun `the sample knows its own active duration`() {
        assertTrue(
            "a 26-minute demo track must produce a positive active duration",
            seeded.dashboardActiveDurationMillis > 0L
        )
    }

    @Test
    fun `the active duration agrees with the aggregate the grid shows beside it`() {
        // The defect's real tell: average speed came off the aggregate and looked right, while the
        // duration cell claimed to know nothing. distance / duration must land on that same speed,
        // which is §5.1's invariant applied to the one ride that could not satisfy it.
        val calc = requireNotNull(seeded.postRideCalculation)
        val hours = seeded.dashboardActiveDurationMillis / 3_600_000.0
        val impliedKph = (calc.distance / 1_000.0) / hours
        val statedKph = calc.avgSpeed * 3.6
        assertEquals(statedKph, impliedKph, statedKph * 0.05)
    }

    @Test
    fun `the sample carries an elevation figure rather than an empty cell`() {
        // §5.2 reserves the absent cell for altitude we never had. This track has an altitude on
        // every point and is genuinely flat, so 0 m is the measured answer, not a guess.
        val elevation = requireNotNull(seeded.postRideCalculation).elevationGainMeters
        assertNotNull("a flat track still has a known gain", elevation)
        assertTrue("flat terrain cannot climb", elevation!! < 1.0)
    }

    @Test
    fun `the sample carries its route shape, so its History card is not generic`() {
        // TASK-246 gave every other path its shape; the seeder was the one it could not reach,
        // because it never called the metadata helper at all.
        val polyline = seeded.dashboardRoutePolyline
        assertNotNull(polyline)
        assertTrue(polyline!!.isNotEmpty())
        assertEquals(fixture.points.size, seeded.dashboardPointCount)
    }
}
