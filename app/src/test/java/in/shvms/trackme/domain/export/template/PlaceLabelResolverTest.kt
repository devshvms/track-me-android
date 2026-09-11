package `in`.shvms.trackme.domain.export.template

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SCOPE_1.8.9 §7, §11 gate 3 — the lookup never involves the true ends, and never returns a street. */
class PlaceLabelResolverTest {

    /** 2 km due north in 100 m steps: the trimmed ends are two points in from each true end. */
    private val points = List(21) { index ->
        GPSPointEntity(
            id = index.toLong(), rideId = 1L, latitude = 12.9 + index * 0.0009, longitude = 77.6,
            altitude = 0.0, accuracy = 5f, speed = 3f, timestamp = index * 30_000L, isPaused = false,
        )
    }

    @Test
    fun `only the trimmed ends are ever looked up`() = runTest {
        val asked = mutableListOf<Double>()
        PlaceLabelResolver { latitude, _ -> asked += latitude; PlaceParts(locality = "Bengaluru") }.resolve(points)
        assertEquals(2, asked.size)
        assertTrue("never the true start", asked.none { it == points.first().latitude })
        assertTrue("never the true finish", asked.none { it == points.last().latitude })
    }

    @Test
    fun `the answer is neighbourhood or coarser, never the street`() = runTest {
        val (start, finish) = PlaceLabelResolver { latitude, _ ->
            if (latitude < 12.91) PlaceParts(subLocality = "Koramangala", thoroughfare = "80 Feet Road")
            else PlaceParts(subLocality = "100 Feet Road", locality = "Bengaluru", thoroughfare = "100 Feet Road")
        }.resolve(points)
        assertEquals("Koramangala", start)
        assertEquals("Bengaluru", finish)
    }

    @Test
    fun `a failed or absent geocoder is no label, not an error`() = runTest {
        assertEquals(null to null, PlaceLabelResolver { _, _ -> error("offline") }.resolve(points))
        assertEquals(null to null, PlaceLabelResolver { _, _ -> null }.resolve(points))
        assertNull(PlaceLabelResolver { _, _ -> PlaceParts() }.resolve(emptyList()).first)
    }
}
