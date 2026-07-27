package `in`.shvms.trackme.ui.history

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import com.google.maps.android.compose.MapType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayExportSettingsTest {
    @Test
    fun `preview ratios map to the supported frame sizes`() {
        assertEquals(1080 to 1080, replayFrameSize(1 to 1))
        assertEquals(1440 to 1080, replayFrameSize(4 to 3))
        assertEquals(1920 to 1080, replayFrameSize(16 to 9))
        assertEquals(1080 to 1920, replayFrameSize(9 to 16))
    }

    @Test
    fun `awkward ratios always produce even dimensions`() {
        listOf(7 to 5, 5 to 7, 11 to 7, 7 to 11, 3 to 2, 2 to 3).forEach { ratio ->
            val (width, height) = replayFrameSize(ratio)
            assertTrue("width for $ratio must be even", width % 2 == 0)
            assertTrue("height for $ratio must be even", height % 2 == 0)
        }
    }

    @Test
    fun `long edge is capped without changing the practical ratio`() {
        val size = replayFrameSize(100 to 1)

        assertEquals(1920, size.first)
        assertEquals(20, size.second)
        assertTrue(maxOf(size.first, size.second) <= 1920)
    }

    @Test
    fun `zero width degrades to a safe square`() {
        assertEquals(1080 to 1080, replayFrameSize(0 to 16))
    }

    @Test
    fun `negative height degrades to a safe square`() {
        assertEquals(1080 to 1080, replayFrameSize(16 to -9))
    }

    @Test
    fun `default portrait capture retains the historical dimensions`() {
        assertEquals(540 to 960, replaySnapshotSize(replayFrameSize(9 to 16)))
    }

    @Test
    fun `capture dimensions preserve the selected landscape ratio`() {
        val frame = replayFrameSize(16 to 9)
        val snapshot = replaySnapshotSize(frame)

        assertEquals(frame.first.toFloat() / frame.second, snapshot.first.toFloat() / snapshot.second, 0.0001f)
    }

    @Test
    fun `capture dimensions preserve the selected square ratio`() {
        val snapshot = replaySnapshotSize(replayFrameSize(1 to 1))

        assertEquals(1f, snapshot.first.toFloat() / snapshot.second, 0.0001f)
    }

    @Test
    fun `normal style maps to the normal sdk type`() {
        assertEquals(com.google.android.gms.maps.GoogleMap.MAP_TYPE_NORMAL, googleMapTypeFor(MapType.NORMAL))
    }

    @Test
    fun `satellite and terrain styles map to distinct sdk types`() {
        val satellite = googleMapTypeFor(MapType.SATELLITE)
        val terrain = googleMapTypeFor(MapType.TERRAIN)

        assertEquals(com.google.android.gms.maps.GoogleMap.MAP_TYPE_SATELLITE, satellite)
        assertEquals(com.google.android.gms.maps.GoogleMap.MAP_TYPE_TERRAIN, terrain)
        assertNotEquals(satellite, terrain)
    }

    @Test
    fun `hybrid style maps to the hybrid sdk type`() {
        assertEquals(com.google.android.gms.maps.GoogleMap.MAP_TYPE_HYBRID, googleMapTypeFor(MapType.HYBRID))
    }

    @Test
    fun `privacy trim route derivation matches the shared trim helper`() {
        val points = listOf(point(3, 3_000), point(1, 1_000), point(2, 2_000))

        assertEquals(
            trimComparisonEndpoints(points).sortedBy { it.timestamp },
            replayRoutePoints(points, applyPrivacyTrim = true)
        )
    }

    @Test
    fun `untrimmed route derivation preserves every point in timestamp order`() {
        val points = listOf(point(3, 3_000), point(1, 1_000), point(2, 2_000))

        assertEquals(points.sortedBy { it.timestamp }, replayRoutePoints(points, applyPrivacyTrim = false))
    }

    @Test
    fun `untrimmed route derivation never applies endpoint privacy`() {
        val points = listOf(point(1, 1_000), point(2, 2_000))

        assertEquals(points, replayRoutePoints(points, applyPrivacyTrim = false))
        assertEquals(points, replayRoutePoints(points, applyPrivacyTrim = true))
    }

    @Test
    fun `frame dimensions remain positive after cap and even rounding`() {
        val size = replayFrameSize(10_000 to 1)

        assertTrue(size.first > 0)
        assertTrue(size.second > 0)
        assertTrue(size.first % 2 == 0 && size.second % 2 == 0)
    }

    private fun point(id: Long, timestamp: Long) = GPSPointEntity(
        id = id,
        rideId = 1L,
        latitude = 50.0 + id * 0.001,
        longitude = 8.0 + id * 0.001,
        altitude = 0.0,
        accuracy = 1f,
        speed = 0f,
        timestamp = timestamp,
        isPaused = false
    )
}
