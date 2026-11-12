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
                "speed": 7.5,
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
        assertEquals(7.5f, snapshot.lastLocation!!.speedMps!!, 0.001f)
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
    fun `returns null on malformed json`() {
        assertNull(LiveSessionViewerRepository.parseSnapshot("sess_bad", "{not-json"))
    }
}
