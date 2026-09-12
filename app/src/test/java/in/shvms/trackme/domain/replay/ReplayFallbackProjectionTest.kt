package `in`.shvms.trackme.domain.replay

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/** TASK-319 — with no map under it, the replay draws the route in the shape it was ridden. */
class ReplayFallbackProjectionTest {

    /** A closed 2 km (east–west) by 1 km (north–south) rectangle at 60° N, as recorded points. */
    private fun rectangle(): List<GPSPointEntity> {
        val latitude = 60.0
        val dLat = 1_000.0 / 111_320.0
        val dLng = 2_000.0 / (111_320.0 * cos(Math.toRadians(latitude)))
        val corners = listOf(0.0 to 0.0, 0.0 to dLng, dLat to dLng, dLat to 0.0, 0.0 to 0.0)
        return corners.mapIndexed { index, (lat, lng) ->
            GPSPointEntity(
                id = index.toLong(), rideId = 1L, latitude = latitude + lat, longitude = 10.0 + lng,
                altitude = 0.0, accuracy = 5f, speed = 5f, timestamp = index * 60_000L, isPaused = false,
            )
        }
    }

    @Test
    fun `a two by one route stays two by one in a tall frame instead of stretching to fill it`() {
        val placed = replayFallbackRoute(rectangle(), width = 1080f, height = 1920f, fullFrame = false)
        val width = placed.maxOf { it.first } - placed.minOf { it.first }
        val height = placed.maxOf { it.second } - placed.minOf { it.second }
        assertEquals(2.0, (width / height).toDouble(), 0.02)
    }

    @Test
    fun `the route stays inside the padded box the overlay leaves clear`() {
        val placed = replayFallbackRoute(rectangle(), width = 1080f, height = 1920f, fullFrame = false)
        assertTrue(placed.all { (x, y) -> x in (1080f * 0.08f - 0.5f)..(1080f * 0.92f + 0.5f) && y in (1920f * 0.18f - 0.5f)..(1920f * 0.84f + 0.5f) })
    }

    @Test
    fun `nothing to draw is nothing`() {
        assertTrue(replayFallbackRoute(emptyList(), 1080f, 1920f, fullFrame = false).isEmpty())
    }
}
