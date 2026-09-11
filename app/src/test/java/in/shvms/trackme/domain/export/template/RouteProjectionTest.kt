package `in`.shvms.trackme.domain.export.template

import `in`.shvms.trackme.domain.processor.RouteCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/**
 * SCOPE_1.8.9 §11 gate 1 — the shape the rider rode is the shape on the canvas, at every ratio, as a
 * pure function with no device. The replay video's old projection failed both halves of this: it
 * stretched each axis independently and plotted raw degrees.
 */
class RouteProjectionTest {

    private val metersPerDegree = 111_320.0

    /** A closed rectangle measured in metres on the ground, centred on [latitude]. */
    private fun rectangle(latitude: Double, widthMeters: Double, heightMeters: Double): List<RouteCoordinate> {
        val dLat = heightMeters / metersPerDegree
        val dLng = widthMeters / (metersPerDegree * cos(Math.toRadians(latitude)))
        val south = latitude - dLat / 2
        val north = latitude + dLat / 2
        return listOf(
            RouteCoordinate(south, 10.0),
            RouteCoordinate(south, 10.0 + dLng),
            RouteCoordinate(north, 10.0 + dLng),
            RouteCoordinate(north, 10.0),
            RouteCoordinate(south, 10.0),
        )
    }

    private fun extent(points: List<PixelPoint>): Pair<Float, Float> =
        (points.maxOf { it.x } - points.minOf { it.x }) to (points.maxOf { it.y } - points.minOf { it.y })

    @Test
    fun `a square on the ground is a square on the canvas at every latitude`() {
        for (latitude in listOf(0.0, 12.97, 51.5, 60.0)) {
            val route = rectangle(latitude, 1000.0, 1000.0)
            val (width, height) = extent(RouteProjection.fit(route, PixelBox(0f, 0f, 1000f, 1000f))!!.project(route))
            assertEquals("aspect at latitude $latitude", 1.0, (width / height).toDouble(), 0.01)
        }
    }

    @Test
    fun `a two-by-one route stays two by one instead of stretching to fill a tall frame`() {
        val route = rectangle(60.0, 2000.0, 1000.0)
        for (box in listOf(PixelBox(0f, 0f, 900f, 1600f), PixelBox(0f, 0f, 1080f, 1080f), PixelBox(0f, 0f, 1080f, 600f))) {
            val (width, height) = extent(RouteProjection.fit(route, box)!!.project(route))
            assertEquals("aspect in $box", 2.0, (width / height).toDouble(), 0.02)
            assertTrue("fits inside $box", width <= box.width + 0.5f && height <= box.height + 0.5f)
        }
    }

    @Test
    fun `the binding axis fills the box and the shape is centred on the other`() {
        val route = rectangle(12.97, 2000.0, 1000.0)
        val box = PixelBox(100f, 200f, 1000f, 1800f)
        val projected = RouteProjection.fit(route, box)!!.project(route)
        val (width, height) = extent(projected)
        assertEquals(box.width, width, 0.5f)
        val centreY = (projected.maxOf { it.y } + projected.minOf { it.y }) / 2f
        assertEquals((box.top + box.bottom) / 2f, centreY, 0.5f)
        assertTrue(height < box.height)
    }

    @Test
    fun `north is up`() {
        val south = RouteCoordinate(12.90, 77.60)
        val north = RouteCoordinate(12.95, 77.60)
        val projection = RouteProjection.fit(listOf(south, north), PixelBox(0f, 0f, 500f, 500f))!!
        assertTrue(projection.project(north).y < projection.project(south).y)
    }

    @Test
    fun `a due-north line is as tall as the box allows and centred across it`() {
        val line = listOf(RouteCoordinate(12.90, 77.60), RouteCoordinate(12.95, 77.60))
        val box = PixelBox(0f, 0f, 400f, 800f)
        val projected = RouteProjection.fit(line, box)!!.project(line)
        assertEquals(800f, extent(projected).second, 0.5f)
        assertTrue(projected.all { kotlin.math.abs(it.x - 200f) < 0.5f })
    }

    @Test
    fun `a single point lands in the centre`() {
        val point = RouteCoordinate(12.9, 77.6)
        val projected = RouteProjection.fit(listOf(point), PixelBox(0f, 0f, 300f, 500f))!!.project(point)
        assertEquals(150f, projected.x, 0.01f)
        assertEquals(250f, projected.y, 0.01f)
    }

    @Test
    fun `nothing to fit or nowhere to fit it is null rather than a guess`() {
        assertNull(RouteProjection.fit(emptyList(), PixelBox(0f, 0f, 10f, 10f)))
        assertNull(RouteProjection.fit(listOf(RouteCoordinate(1.0, 1.0)), PixelBox(0f, 0f, 0f, 10f)))
    }

    @Test
    fun `decimation keeps both ends and drops neighbours closer than a step`() {
        val points = listOf(PixelPoint(0f, 0f), PixelPoint(0.5f, 0f), PixelPoint(3f, 0f), PixelPoint(3.2f, 0f), PixelPoint(3.3f, 0f))
        assertEquals(listOf(0, 2, 4), decimate(points, minStep = 2f))
        assertEquals(listOf(0, 1), decimate(points.take(2), minStep = 2f))
    }
}
