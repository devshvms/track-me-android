package `in`.shvms.trackme.ui.history

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.RideEntity
import `in`.shvms.trackme.data.local.entity.RideWithPoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiRideComparisonModelTest {
    @Test
    fun `routes are chronological with stable id tie break and capped at eight`() {
        val rides = (0L until 10L).map { id -> ride(id, startTime = if (id < 2) 100L else 1_000L - id) }

        val routes = prepareComparisonRoutes(rides)

        assertEquals(8, routes.size)
        assertEquals("A", routes.first().label)
        val expected = rides.sortedWith(compareBy<RideWithPoints> { it.ride.startTime }.thenBy { it.ride.id })
            .take(MAX_COMPARISON_RIDES)
            .map { it.ride.id }
        assertEquals(expected, routes.map { it.ride.ride.id })
    }

    @Test
    fun `connectors skip sparse routes without crashing`() {
        val first = ride(1, 1_000, points = listOf(point(1, 1.0, 1.0), point(2, 1.001, 1.001)))
        val sparse = ride(2, 2_000, points = emptyList())
        val third = ride(3, 3_000, points = listOf(point(3, 2.0, 2.0), point(4, 2.001, 2.001)))

        val routes = prepareComparisonRoutes(listOf(third, sparse, first))
        val connectors = comparisonConnectors(routes)

        assertEquals(0, connectors.size)
        assertTrue(routes.all { it.points.isNotEmpty() || it.ride.ride.id == 2L })
    }

    @Test
    fun `privacy trim preserves short routes`() {
        val points = listOf(point(1, 50.0, 8.0), point(2, 50.0001, 8.0001))

        assertEquals(points, trimComparisonEndpoints(points))
    }

    @Test
    fun `aggregate preview legend is optional and capped`() {
        val routes = prepareComparisonRoutes((0L until 10L).map { id ->
            ride(id, startTime = id, points = listOf(point(id, 50.0 + id, 8.0 + id)))
        })

        assertTrue(aggregatePreviewLegend(routes, "Ride History", showLegend = false).isEmpty())
        val legend = aggregatePreviewLegend(routes, "Ride History", showLegend = true)
        assertEquals(MAX_COMPARISON_RIDES, legend.size)
        assertEquals("A", legend.first().first)
    }

    private fun ride(id: Long, startTime: Long, points: List<GPSPointEntity> = listOf(point(id, 50.0 + id, 8.0 + id))): RideWithPoints =
        RideWithPoints(RideEntity(id = id, startTime = startTime, endTime = startTime + 1_000), points)

    private fun point(id: Long, latitude: Double, longitude: Double) = GPSPointEntity(
        id = id,
        rideId = id,
        latitude = latitude,
        longitude = longitude,
        altitude = 0.0,
        accuracy = 1f,
        speed = 0f,
        timestamp = id,
        isPaused = false
    )
}
