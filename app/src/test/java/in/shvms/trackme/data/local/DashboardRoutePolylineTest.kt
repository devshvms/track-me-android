package `in`.shvms.trackme.data.local

import com.google.maps.android.PolyUtil
import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * TASK-231. The History thumbnail is only as honest as this string: if the round trip loses or
 * reorders points, every card is quietly wrong in a way that still looks like a route.
 */
class DashboardRoutePolylineTest {

    private fun point(latitude: Double, longitude: Double, timestamp: Long = 0L) = GPSPointEntity(
        rideId = 1L,
        latitude = latitude,
        longitude = longitude,
        altitude = 0.0,
        accuracy = 5f,
        speed = 4f,
        timestamp = timestamp,
        isPaused = false,
    )

    @Test fun `round trip preserves the shape within display precision`() {
        val route = listOf(
            point(12.971598, 77.594566),
            point(12.972104, 77.596012),
            point(12.973550, 77.597881),
            point(12.975012, 77.598004),
            point(12.976431, 77.596120),
        )
        val decoded = PolyUtil.decode(dashboardRoutePolylineFromPoints(route)!!)
        assertEquals(route.size, decoded.size)
        route.zip(decoded).forEach { (expected, actual) ->
            assertTrue(abs(expected.latitude - actual.latitude) < 1e-5)
            assertTrue(abs(expected.longitude - actual.longitude) < 1e-5)
        }
    }

    @Test fun `southern and western hemispheres survive the round trip`() {
        val south = listOf(point(-33.868820, 151.209290), point(-33.872500, 151.220100))
        assertTrue(PolyUtil.decode(dashboardRoutePolylineFromPoints(south)!!).all { it.latitude < 0.0 })
        val west = listOf(point(37.7749, -122.4194), point(37.8044, -122.2712))
        assertTrue(PolyUtil.decode(dashboardRoutePolylineFromPoints(west)!!).all { it.longitude < 0.0 })
    }

    @Test fun `fewer than two points is not a route`() {
        assertNull(dashboardRoutePolylineFromPoints(emptyList()))
        assertNull(dashboardRoutePolylineFromPoints(listOf(point(12.9, 77.5))))
    }

    @Test fun `a dense ride is thinned to the budget, keeping both endpoints`() {
        val dense = (0 until 5_000).map { point(12.9 + it * 1e-5, 77.5 + it * 1e-5, it.toLong()) }
        val decoded = PolyUtil.decode(dashboardRoutePolylineFromPoints(dense)!!)
        assertEquals(DASHBOARD_ROUTE_POLYLINE_POINTS, decoded.size)
        assertTrue(abs(dense.first().latitude - decoded.first().latitude) < 1e-5)
        assertTrue(abs(dense.last().latitude - decoded.last().latitude) < 1e-5)
        assertTrue(decoded.zipWithNext().all { (a, b) -> a.latitude <= b.latitude })
    }

    @Test fun `a short ride is left alone`() {
        val route = (0 until 5).map { point(12.9 + it * 1e-4, 77.5 + it * 1e-4, it.toLong()) }
        assertEquals(5, PolyUtil.decode(dashboardRoutePolylineFromPoints(route)!!).size)
    }

    @Test fun `the stored string stays small enough to sit on the ride row`() {
        // The whole point of storing it: a 500-activity History must stay a single-row read.
        val dense = (0 until 20_000).map { point(12.9 + it * 1e-5, 77.5 + it * 1e-5, it.toLong()) }
        val encoded = dashboardRoutePolylineFromPoints(dense)!!
        assertTrue("encoded thumbnail was ${encoded.length} chars", encoded.length < 400)
    }
}
