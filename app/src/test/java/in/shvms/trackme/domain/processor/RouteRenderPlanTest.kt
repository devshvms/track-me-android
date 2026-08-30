package `in`.shvms.trackme.domain.processor

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.PauseOrigin
import `in`.shvms.trackme.domain.model.RidePersona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRenderPlanTest {
    @Test
    fun `short and long automatic pauses are circles only`() {
        listOf(1, 8).forEach { pauseCount ->
            val points = buildList {
                add(point(0, 12.97, 77.59))
                repeat(pauseCount) { add(point(it + 1, 12.9701, 77.59, true, PauseOrigin.AUTO)) }
                add(point(pauseCount + 1, 12.9702, 77.59))
            }
            val plan = RouteRenderPlan.build(points, RidePersona.WALK)
            assertEquals("pauseCount=$pauseCount", 1, plan.pauseMarkers.size)
            assertTrue("pauseCount=$pauseCount", plan.dottedJoins.isEmpty())
            assertEquals(1, plan.solidRuns.size)
        }
    }

    @Test
    fun `manual boundary is one dotted join and no auto circle`() {
        val points = listOf(
            point(0, 12.97, 77.59),
            point(1, 12.9701, 77.59, true, PauseOrigin.MANUAL),
            point(240, 12.9755, 77.59),
            point(241, 12.9756, 77.59),
        )
        val plan = RouteRenderPlan.build(points, RidePersona.WALK)
        assertEquals(2, plan.solidRuns.size)
        assertEquals(1, plan.dottedJoins.size)
        assertTrue(plan.pauseMarkers.isEmpty())
        assertEquals(RouteCoordinate(12.9701, 77.59), plan.dottedJoins.single().first())
        assertEquals(RouteCoordinate(12.9755, 77.59), plan.dottedJoins.single().last())
    }

    @Test
    fun `automatic pause next to true gps gap preserves both semantics`() {
        val points = listOf(
            point(0, 12.97, 77.59),
            point(1, 12.9701, 77.59, true, PauseOrigin.AUTO),
            point(121, 13.07, 77.59),
            point(122, 13.0701, 77.59),
        )
        val plan = RouteRenderPlan.build(points, RidePersona.WALK)
        assertEquals(1, plan.pauseMarkers.size)
        assertEquals(1, plan.dottedJoins.size)
    }

    @Test
    fun `identical input produces structurally identical ordered geometry`() {
        val points = listOf(
            point(0, 0.0, 179.9),
            point(1, 0.0, -179.9),
            point(2, 0.001, -179.8, true, PauseOrigin.AUTO),
        )
        assertEquals(
            RouteRenderPlan.build(points, RidePersona.CYCLING),
            RouteRenderPlan.build(points, RidePersona.CYCLING),
        )
    }

    @Test
    fun `empty and one-point inputs are not renderable`() {
        assertTrue(RouteRenderPlan.build(emptyList(), RidePersona.AUTO).isEmpty)
        assertTrue(RouteRenderPlan.build(listOf(point(0, 1.0, 1.0)), RidePersona.AUTO).isEmpty)
    }

    @Test
    fun `non-monotonic timestamps stay in stored order without crashing`() {
        val points = listOf(
            point(3, 1.0, 1.0),
            point(1, 1.1, 1.1),
            point(2, 1.2, 1.2),
        )
        val plan = RouteRenderPlan.build(points, RidePersona.AUTO)
        assertFalse(plan.isEmpty)
        assertEquals(points.map { RouteCoordinate(it.latitude, it.longitude) }, plan.solidRuns.single())
    }

    @Test
    fun `bounds are exactly the visible plan inputs`() {
        val points = listOf(
            point(0, 10.0, 10.0),
            point(1, 10.1, 10.1, true, PauseOrigin.AUTO),
            point(2, 10.2, 10.2),
        )
        val plan = RouteRenderPlan.build(points, RidePersona.WALK)
        assertTrue(plan.boundsLimits.containsAll(plan.solidRuns.flatten()))
        assertTrue(plan.boundsLimits.containsAll(plan.dottedJoins.flatten()))
        assertTrue(plan.boundsLimits.containsAll(plan.pauseMarkers))
    }

    private fun point(
        second: Int,
        latitude: Double,
        longitude: Double,
        paused: Boolean = false,
        origin: PauseOrigin? = null,
    ) = GPSPointEntity(
        id = second.toLong(),
        rideId = 1L,
        latitude = latitude,
        longitude = longitude,
        altitude = 0.0,
        accuracy = 5f,
        speed = if (paused) 0f else 5f,
        timestamp = second * 1_000L,
        isPaused = paused,
        pauseOrigin = origin,
    )
}
