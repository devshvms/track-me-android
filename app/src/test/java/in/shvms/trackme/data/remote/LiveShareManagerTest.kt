package `in`.shvms.trackme.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveShareManagerTest {
    @Test
    fun formatGracefulErrorExplainsReauthenticationForUnauthorizedResponse() {
        val error = LiveShareHttpException(401)

        assertEquals(
            "Your sign-in expired. Please sign in again to share your location.",
            LiveShareManager.formatGracefulError(error)
        )
        assertEquals(true, LiveShareManager.isAuthenticationError(error))
    }

    @Test
    fun nonUnauthorizedResponsesAreNotTreatedAsAuthenticationFailures() {
        val error = LiveShareHttpException(500)

        assertEquals(false, LiveShareManager.isAuthenticationError(error))
    }

    @Test
    fun buildStartRequestBodyCapsDeduplicatesAndTrimsWatchers() {
        val body = LiveShareManager.buildStartRequestBody(
            durationMinutes = 30,
            username = "Shubham",
            watcherUids = listOf(" uid1 ", "", "uid2", "uid1", "uid3", "uid4", "uid5", "uid6"),
        )
        assertEquals(30, body.getInt("durationMinutes"))
        assertEquals("Shubham", body.getString("username"))
        val arr = body.getJSONArray("watcherUids")
        assertEquals(5, arr.length())
        assertEquals("uid1", arr.getString(0))
        assertEquals("uid5", arr.getString(4))
    }

    @Test
    fun parseStartResponseReadsNotifiedWatchersCount() {
        val parsed = LiveShareManager.parseStartResponse(
            """
            {
              "sessionId": "abc123",
              "shareLink": "https://trackme.shvms.in/live/abc123",
              "expiresAt": "2026-11-12T18:00:00Z",
              "notifiedWatchersCount": 3
            }
            """.trimIndent()
        )
        assertEquals("abc123", parsed.sessionId)
        assertEquals("https://trackme.shvms.in/live/abc123", parsed.shareLink)
        assertEquals(3, parsed.notifiedWatchersCount)
    }

    @Test
    fun parseActiveViewersHandlesValidMissingAndMalformedPayloads() {
        assertEquals(4, LiveShareManager.parseActiveViewers("""{"activeViewers": 4}""", fallback = 0))
        assertEquals(2, LiveShareManager.parseActiveViewers("""{"ok": true}""", fallback = 2))
        assertEquals(0, LiveShareManager.parseActiveViewers("""{"activeViewers": -1}""", fallback = 5))
        assertEquals(1, LiveShareManager.parseActiveViewers("not-json", fallback = 1))
    }
}
