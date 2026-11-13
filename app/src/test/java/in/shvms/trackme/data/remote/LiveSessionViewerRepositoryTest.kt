package `in`.shvms.trackme.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSessionViewerRepositoryTest {

    @Test
    fun `parses active snapshot with lng or lon coordinates and path`() {
        val json = """
            {
              "username": "Shivam",
              "status": "active",
              "destination": "Indiranagar",
              "lastLocation": {
                "lat": 12.9716,
                "lng": 77.5946,
                "speed": 36.0,
                "heading": 142.0,
                "batteryLevel": 81,
                "powerSaveMode": false
              },
              "path": [
                [12.9700, 77.5900],
                { "lat": 12.9716, "lon": 77.5946 }
              ]
            }
        """.trimIndent()

        val snapshot = LiveSessionViewerRepository.parseSnapshot("sess_1", json)
        assertNotNull(snapshot)
        snapshot!!
        assertEquals("sess_1", snapshot.sessionId)
        assertEquals("Shivam", snapshot.username)
        assertTrue(snapshot.isActive)
        assertEquals("Indiranagar", snapshot.destination)
        assertEquals(36.0f, snapshot.lastLocation!!.speedKmh!!, 0.001f)
        assertEquals(10.0f, snapshot.lastLocation!!.speedMps!!, 0.001f)
        assertEquals(142.0f, snapshot.lastLocation!!.headingDeg!!, 0.01f)
        assertEquals(81, snapshot.lastLocation!!.batteryLevel)
        assertEquals(2, snapshot.path.size)
        assertEquals(77.5900, snapshot.path.first().lng, 0.0001)
        assertEquals(77.5946, snapshot.path.last().lng, 0.0001)
    }

    @Test
    fun `parses ended snapshot with endReason and stopReason`() {
        val json = """
            {
              "username": "Rider",
              "status": "ended",
              "endReason": "stopped_by_user",
              "stopReason": "manual",
              "lastLocation": { "lat": 12.9, "lon": 77.6 }
            }
        """.trimIndent()

        val snapshot = LiveSessionViewerRepository.parseSnapshot("sess_2", json)
        assertNotNull(snapshot)
        assertFalse(snapshot!!.isActive)
        assertEquals("stopped_by_user", snapshot.endReason)
        assertEquals("manual", snapshot.stopReason)
        assertEquals(1, snapshot.path.size)
    }

    @Test
    fun `computes freshness age clamped at zero and flags delayed updates`() {
        val nowMs = 1_700_000_100_000L // exact epoch ms
        val freshIso = java.time.Instant.ofEpochMilli(nowMs - 12_000L).toString()
        val staleIso = java.time.Instant.ofEpochMilli(nowMs - 50_000L).toString()
        val futureIso = java.time.Instant.ofEpochMilli(nowMs + 15_000L).toString()

        assertEquals(12, LiveSessionViewerRepository.ageSeconds(freshIso, nowMs))
        assertEquals(0, LiveSessionViewerRepository.ageSeconds(futureIso, nowMs))
        assertNull(LiveSessionViewerRepository.ageSeconds("not-a-timestamp", nowMs))

        assertFalse(LiveSessionViewerRepository.isDelayedUpdate(powerSaveMode = false, timestampIso = freshIso, nowEpochMillis = nowMs))
        assertTrue(LiveSessionViewerRepository.isDelayedUpdate(powerSaveMode = false, timestampIso = staleIso, nowEpochMillis = nowMs))
        assertTrue(LiveSessionViewerRepository.isDelayedUpdate(powerSaveMode = true, timestampIso = freshIso, nowEpochMillis = nowMs))
    }

    @Test
    fun `returns null on malformed json`() {
        assertNull(LiveSessionViewerRepository.parseSnapshot("sess_bad", "{not-json"))
    }

    @Test
    fun `computes viewer backoff delay with progression and jitter ceiling`() {
        val noJitter = { 0.0 }
        // 0 errors -> base (5000ms)
        assertEquals(5000L, LiveSessionViewerRepository.computeViewerBackoffDelay(0, randomProvider = noJitter))
        assertEquals(5000L, LiveSessionViewerRepository.computeViewerBackoffDelay(-1, randomProvider = noJitter))

        // 1 error -> base + 1000 = 6000ms
        assertEquals(6000L, LiveSessionViewerRepository.computeViewerBackoffDelay(1, randomProvider = noJitter))

        // 2 errors -> base + 3000 = 8000ms
        assertEquals(8000L, LiveSessionViewerRepository.computeViewerBackoffDelay(2, randomProvider = noJitter))

        // 3 errors -> base + 6000 = 11000ms
        assertEquals(11000L, LiveSessionViewerRepository.computeViewerBackoffDelay(3, randomProvider = noJitter))

        // 4 errors -> base + 10000 = 15000ms
        assertEquals(15000L, LiveSessionViewerRepository.computeViewerBackoffDelay(4, randomProvider = noJitter))

        // 8 errors -> clamped to 15000ms
        assertEquals(15000L, LiveSessionViewerRepository.computeViewerBackoffDelay(8, randomProvider = noJitter))

        // Injects jitter without exceeding max
        val halfJitter = { 0.5 }
        assertEquals(6250L, LiveSessionViewerRepository.computeViewerBackoffDelay(1, randomProvider = halfJitter))
        assertEquals(15000L, LiveSessionViewerRepository.computeViewerBackoffDelay(4, randomProvider = halfJitter))
    }

    @Test
    fun `parses timestamp ms from numbers and iso strings`() {
        val nowMs = 1_700_000_100_000L
        val isoStr = java.time.Instant.ofEpochMilli(nowMs).toString()

        assertEquals(nowMs, LiveSessionViewerRepository.parseTimestampMs(nowMs.toString()))
        assertEquals(nowMs, LiveSessionViewerRepository.parseTimestampMs(isoStr))
        assertNull(LiveSessionViewerRepository.parseTimestampMs(null))
        assertNull(LiveSessionViewerRepository.parseTimestampMs(""))
        assertNull(LiveSessionViewerRepository.parseTimestampMs("not_a_time"))
        assertNull(LiveSessionViewerRepository.parseTimestampMs("-500"))
    }

    @Test
    fun `detects stale signals when inactive or older than threshold`() {
        val nowMs = 1_700_000_100_000L
        val freshIso = java.time.Instant.ofEpochMilli(nowMs - 30_000L).toString() // 30s ago
        val staleIso = java.time.Instant.ofEpochMilli(nowMs - 190_000L).toString() // > 3 min ago

        // Inactive ride never flags stale
        assertFalse(LiveSessionViewerRepository.isStaleSignal(isActive = false, timestampIso = staleIso, nowEpochMillis = nowMs))

        // Active ride: fresh vs stale
        assertFalse(LiveSessionViewerRepository.isStaleSignal(isActive = true, timestampIso = freshIso, nowEpochMillis = nowMs))
        assertTrue(LiveSessionViewerRepository.isStaleSignal(isActive = true, timestampIso = staleIso, nowEpochMillis = nowMs))
    }

    @Test
    fun `formats trip duration accurately across minutes and hours`() {
        val startMs = 1_700_000_000_000L

        // Under 1 minute
        assertEquals("< 1m", LiveSessionViewerRepository.formatDuration(startMs.toString(), (startMs + 45_000L).toString()))

        // 42 minutes
        assertEquals("42m", LiveSessionViewerRepository.formatDuration(startMs.toString(), (startMs + 42 * 60_000L).toString()))

        // Exact hour
        assertEquals("2h", LiveSessionViewerRepository.formatDuration(startMs.toString(), (startMs + 120 * 60_000L).toString()))

        // Hours and minutes
        assertEquals("1h 15m", LiveSessionViewerRepository.formatDuration(startMs.toString(), (startMs + 75 * 60_000L).toString()))

        // Invalid or null
        assertNull(LiveSessionViewerRepository.formatDuration(null))
        assertNull(LiveSessionViewerRepository.formatDuration("invalid"))
    }

    @Test
    fun `parses startedAt and formats snapshot duration`() {
        val startMs = 1_700_000_000_000L
        val nowMs = startMs + 25 * 60_000L // 25m later
        val json = """
            {
              "username": "Rider",
              "status": "active",
              "startedAt": "$startMs",
              "lastLocation": {
                "lat": 12.97,
                "lng": 77.59,
                "timestamp": "${java.time.Instant.ofEpochMilli(nowMs).toString()}"
              }
            }
        """.trimIndent()

        val snapshot = LiveSessionViewerRepository.parseSnapshot("sess_dur", json)
        assertNotNull(snapshot)
        assertEquals("25m", snapshot!!.formatDuration(nowMs))
        assertFalse(snapshot.isStaleSignal(nowMs))
    }
}
