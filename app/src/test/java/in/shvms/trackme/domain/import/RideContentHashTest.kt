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
    fun `a different ride hashes differently`() {
        val later = track.map { it.copy(timestamp = it.timestamp + 3_600_000L) }
        assertNotEquals(RideContentHash.of(track), RideContentHash.of(later))
    }

    @Test
    fun `a mid-track deviation is NOT distinguished, and that is the trade`() {
        // Honest about the limit. Identity is sample count, instants and endpoints, so two rides
        // that start and finish in the same 110 m, take the same number of samples, and record every
        // one at the same millisecond, are treated as the same ride even if the middle differs.
        // Reaching that state by accident is not plausible; hashing every coordinate to rule it out
        // is what broke the export-and-reimport case this class exists to catch.
        val detour = track.toMutableList().also { it[1] = it[1].copy(latitude = 12.99000) }
        assertEquals(RideContentHash.of(track), RideContentHash.of(detour))
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
    fun `a lossy GPX round-trip does not change identity`() {
        // The case that failed on a device: exporting to six-decimal GPX and importing back tips
        // points that sit near a rounding boundary. Hashing every coordinate made one flipped point
        // change the whole digest; 18 of 361 flipped, so the re-import was not recognised.
        val roundTripped = track.mapIndexed { index, point ->
            val nudge = if (index % 3 == 0) 0.0000006 else -0.0000004
            point.copy(latitude = point.latitude + nudge, longitude = point.longitude - nudge)
        }
        assertEquals(RideContentHash.of(track), RideContentHash.of(roundTripped))
    }

    @Test
    fun `a ride in another place at the same instants is not the same ride`() {
        val elsewhere = track.map { it.copy(latitude = it.latitude + 0.5, longitude = it.longitude + 0.5) }
        assertNotEquals(RideContentHash.of(track), RideContentHash.of(elsewhere))
    }

    @Test
    fun `a different number of samples is a different track`() {
        assertNotEquals(RideContentHash.of(track), RideContentHash.of(track.dropLast(1)))
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
