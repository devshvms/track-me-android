package `in`.shvms.trackme.domain.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveShareLinkTest {

    @Test
    fun `parses https live session link and ignores trailing slash`() {
        val parsed = LiveShareLink.parse("https://trackme.shvms.in/live/abc_12345/")
        assertEquals(LiveShareDeepLink.Session("abc_12345"), parsed)
    }

    @Test
    fun `parses custom trackme live session uri in both path and query forms`() {
        assertEquals(
            LiveShareDeepLink.Session("sess_9876"),
            LiveShareLink.parse("trackme://live/sess_9876"),
        )
        assertEquals(
            LiveShareDeepLink.Session("sess_9876"),
            LiveShareLink.parse("trackme://live?sessionId=sess_9876"),
        )
    }

    @Test
    fun `parses watcher invite uri without mistaking watcher segment for sessionId`() {
        val parsed = LiveShareLink.parse(
            "https://trackme.shvms.in/live/watcher?inviterUid=uid_123&inviterName=Shivam+Sharma"
        )
        assertTrue(parsed is LiveShareDeepLink.WatcherInvite)
        val invite = parsed as LiveShareDeepLink.WatcherInvite
        assertEquals("uid_123", invite.inviterUid)
        assertEquals("Shivam Sharma", invite.inviterName)

        assertNull(LiveShareLink.parse("https://trackme.shvms.in/live/watcher"))
    }

    @Test
    fun `parses live_share_started notification extras with fallback to shareUrl`() {
        assertEquals(
            LiveShareDeepLink.Session("push_sess_1"),
            LiveShareLink.parse(
                uriString = null,
                extraSessionId = "push_sess_1",
                extraType = "live_share_started",
                extraShareUrl = "https://trackme.shvms.in/live/push_sess_1",
            ),
        )
        assertEquals(
            LiveShareDeepLink.Session("fallback_sess"),
            LiveShareLink.parse(
                uriString = null,
                extraSessionId = null,
                extraType = "live_share_started",
                extraShareUrl = "https://trackme.shvms.in/live/fallback_sess",
            ),
        )
    }

    @Test
    fun `rejects foreign hosts and malformed session ids`() {
        assertNull(LiveShareLink.parse("https://evil.example/live/abc_12345"))
        assertNull(LiveShareLink.parse("https://trackme.shvms.in/live/a"))
    }
}
