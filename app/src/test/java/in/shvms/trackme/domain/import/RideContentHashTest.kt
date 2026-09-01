package `in`.shvms.trackme.domain.`import`

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TASK-275: identity by track content, so the duplicate checks cannot be edited around. */
class RideContentHashTest {

    private fun point(lat: Double, lon: Double, t: Long, speed: Float = 0f, alt: Double = 0.0) =
        GPSPointEntity(
            rideId = 0,
            latitude = lat,
            longitude = lon,
            altitude = alt,
            accuracy = 0f,
            speed = speed,
            timestamp = t,
            isPaused = false,
        )

    private val track = listOf(
        point(12.97160, 77.59460, 1_000L),
        point(12.97250, 77.59530, 11_000L),
        point(12.97340, 77.59610, 21_000L),
    )

    @Test
    fun `same track hashes the same`() {
        assertEquals(RideContentHash.of(track), RideContentHash.of(track.map { it.copy() }))
    }

    @Test
    fun `a different track hashes differently`() {
        val moved = track.toMutableList().also { it[1] = it[1].copy(latitude = 12.98000) }
        assertNotEquals(RideContentHash.of(track), RideContentHash.of(moved))
    }

    @Test
    fun `point order does not change identity`() {
        assertEquals(RideContentHash.of(track), RideContentHash.of(track.reversed()))
    }

    @Test
    fun `recomputed fields do not change identity`() {
        // Speed, accuracy and altitude are the fields another tool is most likely to recompute or
        // drop. A ride round-tripped through one is still the same ride.
        val stripped = track.map { it.copy(speed = 9.9f, altitude = 812.5, accuracy = 4f) }
        assertEquals(RideContentHash.of(track), RideContentHash.of(stripped))
    }

    @Test
    fun `sub-metre formatting noise does not change identity`() {
        // A re-export that differs in the sixth decimal is the same track; five decimals is ~1.1 m.
        val jittered = track.map { it.copy(latitude = it.latitude + 0.000001) }
        assertEquals(RideContentHash.of(track), RideContentHash.of(jittered))
    }

    @Test
    fun `a real move does change identity`() {
        val moved = track.map { it.copy(latitude = it.latitude + 0.001) }
        assertNotEquals(RideContentHash.of(track), RideContentHash.of(moved))
    }

    @Test
    fun `too short to identify returns null`() {
        // One point cannot distinguish two activities, so the caller falls back rather than
        // treating every one-point import as a duplicate of every other.
        assertNull(RideContentHash.of(emptyList()))
        assertNull(RideContentHash.of(listOf(track.first())))
    }

    @Test
    fun `identity survives a timestamp shift being absent`() {
        // Shifting the clock is a different activity, not the same one re-filed.
        val shifted = track.map { it.copy(timestamp = it.timestamp + 86_400_000L) }
        assertNotEquals(RideContentHash.of(track), RideContentHash.of(shifted))
    }
}
