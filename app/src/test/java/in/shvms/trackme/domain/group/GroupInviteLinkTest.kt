package `in`.shvms.trackme.domain.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SCOPE_1.7.0 §2.4, §6.1 **B5** — the invite arriving from outside the app.
 *
 * Every input here is attacker-supplied: any app or web page can fire an intent at us with
 * whatever it likes, and an exported activity with a BROWSABLE filter is the widest front door
 * this app has. Most of this file is about what happens when the thing on the other side is not
 * a real invite.
 */
class GroupInviteLinkTest {

    private val token = "Zm9vYmFyYmF6cXV4MTIzNA"
    private val appLink = "https://trackme.shvms.in/g"
    private val deepLink = "trackme://group"

    // --- The two shapes that must work -------------------------------------------------------

    @Test
    fun `an App Link carries the token in the fragment`() {
        // A6: the token is never in a path or query, because a server would log it (§10). Over
        // https it rides in the fragment, which browsers do not transmit.
        val invite = GroupInviteLink.parse(uriString = "$appLink#$token", fragment = token)
        assertEquals(token, invite!!.token)
        assertTrue(invite.hasToken)
    }

    @Test
    fun `the custom scheme carries the token as an intent extra`() {
        // Never a URL at all, so there is nothing for anything to log.
        val invite = GroupInviteLink.parse(uriString = deepLink, extraToken = token)
        assertEquals(token, invite!!.token)
    }

    @Test
    fun `a code-only invite is accepted`() {
        val invite = GroupInviteLink.parse(uriString = "$deepLink?c=ABC123", queryCode = "ABC123")
        assertNull(invite!!.token)
        assertEquals("ABC123", invite.code)
    }

    @Test
    fun `a token wins over a code when both are present`() {
        // A token has no 30-minute expiry, no rate limit, and is the actual key material. Taking
        // the code instead would trade a working join for one that can time out.
        val invite = GroupInviteLink.parse(
            uriString = deepLink,
            extraToken = token,
            extraCode = "ABC123",
        )
        assertTrue(invite!!.hasToken)
    }

    // --- Code normalisation, which is contract-critical ----------------------------------------

    @Test
    fun `a code from a link is normalised exactly like a typed one`() {
        // The join code IS key material under the wrapped-token design (crypto contract §2b), so a
        // lower-case code in a link would derive a DIFFERENT key from the same six characters and
        // decrypt nothing — silently.
        for (raw in listOf("abc123", "ABC-123", "abc 123", " ABC123 ")) {
            assertEquals("failed on \"$raw\"", "ABC123", GroupInviteLink.parse(deepLink, queryCode = raw)!!.code)
        }
    }

    @Test
    fun `confusable letters in a link are mapped the same way`() {
        assertEquals("1BC123", GroupInviteLink.parse(deepLink, queryCode = "IBC123")!!.code)
        assertEquals("1BC123", GroupInviteLink.parse(deepLink, queryCode = "lBC123")!!.code)
        assertEquals("0BC123", GroupInviteLink.parse(deepLink, queryCode = "OBC123")!!.code)
    }

    // --- Hostile and malformed input -------------------------------------------------------------

    @Test
    fun `a link with nothing usable produces no invite`() {
        assertNull(GroupInviteLink.parse(uriString = null))
        assertNull(GroupInviteLink.parse(uriString = ""))
        assertNull(GroupInviteLink.parse(uriString = deepLink))
        assertNull(GroupInviteLink.parse(uriString = appLink, fragment = ""))
    }

    @Test
    fun `a malformed token is rejected rather than passed to the crypto`() {
        // A wrong-length token would fail deriveGroupKey with an exception on a path that has no
        // user-facing error. Better to decide here that there is no invite.
        for (bad in listOf("short", "$token-extra", "Zm9vYmFyYmF6cXV4MTIzN", "!!!!!!!!!!!!!!!!!!!!!!", " ")) {
            assertNull("accepted \"$bad\"", GroupInviteLink.parse(appLink, fragment = bad))
        }
    }

    @Test
    fun `a malformed code is rejected`() {
        for (bad in listOf("ABC12", "ABC1234", "ABC12!", "UUUUUU", "")) {
            assertNull("accepted \"$bad\"", GroupInviteLink.parse(deepLink, queryCode = bad))
        }
    }

    @Test
    fun `a hostile intent cannot smuggle anything through`() {
        // The activity is exported and BROWSABLE, so anything on the device can send this. None of
        // it should produce an invite.
        val hostile = listOf(
            "javascript:alert(1)",
            "file:///data/data/in.shvms.trackme/",
            "content://com.other.app/secrets",
            "trackme://group?c=<script>",
            "https://evil.example.com/g",
        )
        for (uri in hostile) {
            // Even reaching parse with a plausible-looking fragment, a non-token is refused.
            assertNull("accepted $uri", GroupInviteLink.parse(uri, fragment = "not-a-token"))
        }
    }

    @Test
    fun `a token from an untrusted host is still only a token`() {
        // Worth being explicit: this function does NOT authorise anything. A token is a bearer
        // credential — whoever holds it was invited — so where the link came from does not change
        // what it is. The relay is the authorisation boundary (§5.2), not the URL bar.
        val invite = GroupInviteLink.parse("https://evil.example.com/g#$token", fragment = token)
        assertEquals(token, invite!!.token)
    }

    @Test
    fun `the deep link prefix matches the manifest`() {
        // If these drift, the button on the landing page targets a scheme nothing handles and the
        // whole growth loop silently falls back to the Play Store.
        assertEquals("trackme://group", GroupInviteLink.deepLinkPrefix())
        assertEquals("trackme", GroupInviteLink.SCHEME)
        assertEquals("group", GroupInviteLink.HOST)
    }
}
