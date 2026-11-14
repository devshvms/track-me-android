package `in`.shvms.trackme.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class TrustedWatchersRepositoryTest {
    @Test
    fun buildInviteLinkEncodesCodeAndName() {
        val url = TrustedWatchersRepository.buildInviteLink("abcDEF1234567890", "Alex Rider")
        assertEquals(
            "https://trackme.shvms.in/live/watcher?code=abcDEF1234567890&inviterName=Alex+Rider",
            url,
        )
    }

    @Test
    fun parseWatcherInviteUriSupportsHttpsAndCustomSchemes() {
        val httpsParsed = TrustedWatchersRepository.parseWatcherInviteUri(
            "https://trackme.shvms.in/live/watcher?code=code_1234567890ab&inviterName=Sam%20K"
        )
        assertNotNull(httpsParsed)
        assertEquals("code_1234567890ab", httpsParsed?.code)
        assertEquals("Sam K", httpsParsed?.inviterName)

        val customParsed = TrustedWatchersRepository.parseWatcherInviteUri(
            "trackme://live/watcher?code=code_0987654321zy"
        )
        assertNotNull(customParsed)
        assertEquals("code_0987654321zy", customParsed?.code)
        assertNull(customParsed?.inviterName)

        assertNull(TrustedWatchersRepository.parseWatcherInviteUri("https://trackme.shvms.in/live/abc"))
        assertNull(TrustedWatchersRepository.parseWatcherInviteUri("https://evil.example/live/watcher?code=code_1234567890ab"))
        assertNull(TrustedWatchersRepository.parseWatcherInviteUri("https://trackme.shvms.in/live/watcher?inviterUid=u1"))
        assertNull(TrustedWatchersRepository.parseWatcherInviteUri("https://trackme.shvms.in/live/watcher?code=short"))
    }

    @Test
    fun parseWatcherInboxResponseParsesActiveInviteOrNull() {
        val parsedIso = TrustedWatchersRepository.parseWatcherInboxResponse(
            """
            {
              "activeInvite": {
                "sessionId": "sess-9",
                "shareUrl": "https://trackme.shvms.in/live/sess-9",
                "riderName": "Maya",
                "startedAt": "2026-11-12T10:00:00Z",
                "expiresAt": "2026-11-12T10:30:00Z"
              }
            }
            """.trimIndent()
        )
        assertNotNull(parsedIso)
        assertEquals("sess-9", parsedIso?.sessionId)
        assertEquals("https://trackme.shvms.in/live/sess-9", parsedIso?.shareUrl)
        assertEquals("Maya", parsedIso?.riderName)
        assertEquals(Instant.parse("2026-11-12T10:00:00Z"), parsedIso?.startedAt)
        assertEquals(Instant.parse("2026-11-12T10:30:00Z"), parsedIso?.expiresAt)

        // H-1: Real backend stores startedAt as epoch millis number alongside ISO-8601 expiresAt.
        val parsedNumeric = TrustedWatchersRepository.parseWatcherInboxResponse(
            """
            {
              "activeInvite": {
                "sessionId": "sess-10",
                "shareUrl": "https://trackme.shvms.in/live/sess-10",
                "riderName": "Alex",
                "startedAt": 1700000000000,
                "expiresAt": "2026-11-12T11:30:00Z"
              }
            }
            """.trimIndent()
        )
        assertNotNull(parsedNumeric)
        assertEquals(Instant.ofEpochMilli(1700000000000L), parsedNumeric?.startedAt)
        assertEquals(Instant.parse("2026-11-12T11:30:00Z"), parsedNumeric?.expiresAt)

        assertNull(TrustedWatchersRepository.parseWatcherInboxResponse("""{"activeInvite":null}"""))
        assertNull(TrustedWatchersRepository.parseWatcherInboxResponse("{}"))
    }
}
