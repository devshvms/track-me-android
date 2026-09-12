package `in`.shvms.trackme.domain.export.template

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.domain.processor.RouteRenderPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/**
 * SCOPE_1.8.9 §11 gate 1, re-run on a **V2-recorded** ride — the obligation §15.8 item 3 left open.
 *
 * Gate 1 was proved against synthetic `RouteCoordinate` lists, which enter the projection already
 * decided. TASK-325 put a second coordinate on every point: the raw recording stays as evidence and
 * the V2 estimator's display geometry is what surfaces draw. So the gate has to be asked again one
 * layer down — *from stored points* — or it certifies a shape the app no longer draws.
 *
 * The divergence used here is far larger than V2's real correction, which moves points by metres.
 * That is deliberate: the question is which field the drawing path reads, and a metre-scale
 * difference would pass whichever answer were true.
 */
class V2RouteShapeGateTest {

    private val metersPerDegree = 111_320.0

    /**
     * A closed rectangle on the ground: `raw` metres in the stored coordinate, `display` metres in
     * the V2 one, so the two disagree about the ride's shape and only one of them can be drawn.
     */
    private fun ride(
        rawWidth: Double, rawHeight: Double,
        displayWidth: Double, displayHeight: Double,
        latitude: Double = 12.97,
    ): List<GPSPointEntity> {
        fun corners(widthMeters: Double, heightMeters: Double): List<Pair<Double, Double>> {
            val dLat = heightMeters / metersPerDegree
            val dLng = widthMeters / (metersPerDegree * cos(Math.toRadians(latitude)))
            val south = latitude - dLat / 2
            val north = latitude + dLat / 2
            return listOf(south to 10.0, south to 10.0 + dLng, north to 10.0 + dLng, north to 10.0, south to 10.0)
        }
        val raw = corners(rawWidth, rawHeight)
        val display = corners(displayWidth, displayHeight)
        return raw.indices.map { index ->
            GPSPointEntity(
                rideId = 1,
                latitude = raw[index].first, longitude = raw[index].second,
                altitude = 900.0, accuracy = 5f, speed = 5f,
                timestamp = index * 10_000L, isPaused = false,
                displayLatitude = display[index].first, displayLongitude = display[index].second,
            )
        }
    }

    private fun aspectOf(points: List<GPSPointEntity>, box: PixelBox): Double {
        val plan = RouteRenderPlan.build(points, RidePersona.CYCLING)
        val line = plan.solidRuns.flatten()
        val projected = RouteProjection.fit(line, box)!!.project(line)
        val width = projected.maxOf { it.x } - projected.minOf { it.x }
        val height = projected.maxOf { it.y } - projected.minOf { it.y }
        return (width / height).toDouble()
    }

    @Test
    fun `a V2 ride is drawn in the display route's shape, not the raw recording's`() {
        // Raw says two-by-one; V2 says square. A template that still read the stored coordinate
        // would come out twice as wide as the ride the rider is shown everywhere else.
        val points = ride(rawWidth = 2000.0, rawHeight = 1000.0, displayWidth = 1000.0, displayHeight = 1000.0)
        assertEquals(1.0, aspectOf(points, PixelBox(0f, 0f, 1080f, 1080f)), 0.01)
    }

    @Test
    fun `the display shape survives every canvas the templates declare`() {
        val points = ride(rawWidth = 1000.0, rawHeight = 1000.0, displayWidth = 2000.0, displayHeight = 1000.0)
        for (canvas in listOf(TemplateCanvas.STORY, TemplateCanvas.PORTRAIT, TemplateCanvas.SQUARE)) {
            val box = traceRouteBoxDesign(canvas)
            assertEquals("aspect on $canvas", 2.0, aspectOf(points, box), 0.02)
            val plan = RouteRenderPlan.build(points, RidePersona.CYCLING)
            val projected = RouteProjection.fit(plan.solidRuns.flatten(), box)!!.project(plan.solidRuns.flatten())
            assertTrue(
                "the route left its box on $canvas",
                projected.all { it.x >= box.left - 0.5f && it.x <= box.right + 0.5f && it.y >= box.top - 0.5f && it.y <= box.bottom + 0.5f },
            )
        }
    }

    /**
     * A legacy ride carries no display coordinate at all. It has to keep drawing exactly as it did
     * before TASK-325 — the fallback is the whole reason the field is nullable.
     */
    @Test
    fun `a pre-V2 ride still draws its raw shape`() {
        val points = ride(rawWidth = 2000.0, rawHeight = 1000.0, displayWidth = 1000.0, displayHeight = 1000.0)
            .map { it.copy(displayLatitude = null, displayLongitude = null) }
        assertEquals(2.0, aspectOf(points, PixelBox(0f, 0f, 1080f, 1080f)), 0.02)
    }
}
