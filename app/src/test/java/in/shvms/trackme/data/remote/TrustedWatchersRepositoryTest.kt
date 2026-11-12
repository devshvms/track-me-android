package `in`.shvms.trackme.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TrustedWatchersRepositoryTest {
    @Test
    fun buildInviteLinkEncodesUidAndName() {
        val url = TrustedWatchersRepository.buildInviteLink("uid-123", "Alex Rider")
        assertEquals(
            "https://trackme.shvms.in/live/watcher?inviterUid=uid-123&inviterName=Alex+Rider",
            url,
        )
    }

    @Test
    fun parseWatcherInviteUriSupportsHttpsAndCustomSchemes() {
        val httpsParsed = TrustedWatchersRepository.parseWatcherInviteUri(
            "https://trackme.shvms.in/live/watcher?inviterUid=u1&inviterName=Sam%20K"
        )
        assertNotNull(httpsParsed)
        assertEquals("u1", httpsParsed?.inviterUid)
        assertEquals("Sam K", httpsParsed?.inviterName)

        val customParsed = TrustedWatchersRepository.parseWatcherInviteUri(
            "trackme://live/watcher?inviterUid=u2"
        )
        assertNotNull(customParsed)
        assertEquals("u2", customParsed?.inviterUid)
        assertNull(customParsed?.inviterName)

        assertNull(TrustedWatchersRepository.parseWatcherInviteUri("https://trackme.shvms.in/live/abc"))
        assertNull(TrustedWatchersRepository.parseWatcherInviteUri("https://evil.example/live/watcher?inviterUid=u1"))
    }

    @Test
    fun parseWatcherInboxResponseParsesActiveInviteOrNull() {
        val parsed = TrustedWatchersRepository.parseWatcherInboxResponse(
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
        assertNotNull(parsed)
        assertEquals("sess-9", parsed?.sessionId)
        assertEquals("https://trackme.shvms.in/live/sess-9", parsed?.shareUrl)
        assertEquals("Maya", parsed?.riderName)

        assertNull(TrustedWatchersRepository.parseWatcherInboxResponse("""{"activeInvite":null}"""))
        assertNull(TrustedWatchersRepository.parseWatcherInboxResponse("{}"))
    }
}
