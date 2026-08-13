package `in`.shvms.trackme.data.remote

import `in`.shvms.trackme.data.crypto.GroupCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * The status slot and the acknowledgement channel — SCOPE_1.7.2 §4.3, §4.4, §4.7; amendments
 * **A26**, **A32**, **A33**.
 *
 * Separate from [GroupWireTest] because it is testing a different contract: that a status can
 * travel with no position behind it, that it cannot be moved between slots by an untrusted relay,
 * and that the caller's own echoed entries — which 1.7.0 discarded — are now read.
 */
class GroupWireStatusTest {

    private val token = "Zm9vYmFyYmF6cXV4MTIzNA"
    private val key = GroupCrypto.deriveGroupKey(token)
    private val self = "uid-me"

    private fun statusEnvelope(uid: String, code: String, stAge: Long?): String =
        GroupCrypto.seal(key, GroupWire.encodeStatus(code, stAge), GroupCrypto.Purpose.Status(uid))

    private fun positionEnvelope(uid: String): String = GroupCrypto.seal(
        key,
        GroupWire.encodePosition(12.9716, 77.5946, 4.2f, 137f, 82, true, true),
        GroupCrypto.Purpose.Position(uid),
    )

    private fun body(
        positions: Map<String, Pair<String, Long>> = emptyMap(),
        statuses: Map<String, Pair<String, Long>> = emptyMap(),
        serverNow: Long? = 1_785_000_010_000L,
    ): String = JSONObject().apply {
        put("state", "LIVE")
        put("expiresAt", 1_785_014_400_000L)
        put("rev", 7)
        put("maxMembers", 5)
        put("nextSyncInSec", 10)
        serverNow?.let { put("serverNow", it) }
        put("positions", JSONObject().apply {
            positions.forEach { (uid, p) -> put(uid, JSONObject().put("e", p.first).put("ts", p.second)) }
        })
        put("statuses", JSONObject().apply {
            statuses.forEach { (uid, s) -> put(uid, JSONObject().put("e", s.first).put("ts", s.second)) }
        })
    }.toString()

    // --- The status slot ------------------------------------------------------------------------

    @Test
    fun `a status decrypts with its age and its own relay timestamp`() {
        val result = GroupWire.parseSync(
            body(statuses = mapOf("uid-ravi" to (statusEnvelope("uid-ravi", "2MEH", 420L) to 1_785_000_000_000L))),
            key,
            self,
        )
        assertEquals(1, result.statuses.size)
        val status = result.statuses.first()
        assertEquals("uid-ravi", status.uid)
        assertEquals("2MEH", status.code)
        assertEquals(420L, status.stAgeSeconds)
        assertEquals(1_785_000_000_000L, status.serverTsMillis)
    }

    @Test
    fun `a status arrives with no position behind it`() {
        // §4.7, and the whole reason for the separate slot: the rider with revoked location
        // permission is precisely the one most likely to need the alert tier.
        val result = GroupWire.parseSync(
            body(statuses = mapOf("uid-ravi" to (statusEnvelope("uid-ravi", "1GNH", 0L) to 1_785_000_000_000L))),
            key,
            self,
        )
        assertTrue(result.positions.isEmpty())
        assertEquals("1GNH", result.statuses.single().code)
    }

    @Test
    fun `an absent stAge is null, not zero — the reboot case`() {
        // §4.3: absent means the sender lost the age. Zero means "set just now". Collapsing them
        // would fabricate a fresh age for a status that might be hours old.
        val result = GroupWire.parseSync(
            body(statuses = mapOf("uid-ravi" to (statusEnvelope("uid-ravi", "2MEH", null) to 1_785_000_000_000L))),
            key,
            self,
        )
        assertNull(result.statuses.single().stAgeSeconds)
    }

    @Test
    fun `a zero stAge is preserved as zero`() {
        val result = GroupWire.parseSync(
            body(statuses = mapOf("uid-ravi" to (statusEnvelope("uid-ravi", "2MEH", 0L) to 1L))),
            key,
            self,
        )
        assertEquals(0L, result.statuses.single().stAgeSeconds)
    }

    @Test
    fun `a status that will not decrypt costs that member their chip, never the sync`() {
        val body = body(
            statuses = mapOf(
                "uid-ravi" to (statusEnvelope("uid-ravi", "2MEH", 10L) to 1L),
                "uid-broken" to ("not-an-envelope" to 2L),
            ),
        )
        val result = GroupWire.parseSync(body, key, self)
        assertEquals(1, result.statuses.size)
        assertTrue("uid-broken" in result.undecryptable)
    }

    @Test
    fun `a relay that omits statuses entirely parses fine`() {
        val result = GroupWire.parseSync("""{"state":"LIVE"}""", key, self)
        assertTrue(result.statuses.isEmpty())
        assertNull(result.ownStatus)
    }

    // --- AAD binding: the relay cannot move an envelope between slots (A2, A33) --------------------

    @Test
    fun `a status envelope cannot be replayed into another member's status slot`() {
        // Without the per-uid context an untrusted relay could copy Ravi's "Need help" under
        // Priya's name — a decryption that SUCCEEDS and produces a lie.
        val result = GroupWire.parseSync(
            body(statuses = mapOf("uid-priya" to (statusEnvelope("uid-ravi", "1GNH", 0L) to 1L))),
            key,
            self,
        )
        assertTrue(result.statuses.isEmpty())
        assertTrue("uid-priya" in result.undecryptable)
    }

    @Test
    fun `a position envelope cannot be replayed into the status slot`() {
        val result = GroupWire.parseSync(
            body(statuses = mapOf("uid-ravi" to (positionEnvelope("uid-ravi") to 1L))),
            key,
            self,
        )
        assertTrue(result.statuses.isEmpty())
        assertTrue("uid-ravi" in result.undecryptable)
    }

    // --- The acknowledgement channel (A13, §4.4) ---------------------------------------------------

    @Test
    fun `our own position is read back rather than discarded`() {
        // A13 has the relay return it deliberately — "the only self-diagnostic there is" — and
        // 1.7.0 threw it away. It is what separates "the relay is reachable" from "the relay took
        // my position".
        val result = GroupWire.parseSync(
            body(positions = mapOf(self to (positionEnvelope(self) to 1_785_000_000_000L))),
            key,
            self,
        )
        assertNotNull(result.ownPosition)
        assertEquals(1_785_000_000_000L, result.ownPosition!!.serverTsMillis)
    }

    @Test
    fun `our own position is still kept off the map`() {
        // §2.6 of 1.7.0: "we never draw ourselves twice". Read, but not rendered.
        val result = GroupWire.parseSync(
            body(
                positions = mapOf(
                    self to (positionEnvelope(self) to 1L),
                    "uid-ravi" to (positionEnvelope("uid-ravi") to 2L),
                ),
            ),
            key,
            self,
        )
        assertEquals(1, result.positions.size)
        assertEquals("uid-ravi", result.positions.single().uid)
    }

    @Test
    fun `our own status is read back separately from everyone else's`() {
        val result = GroupWire.parseSync(
            body(
                statuses = mapOf(
                    self to (statusEnvelope(self, "1GNH", 5L) to 1L),
                    "uid-ravi" to (statusEnvelope("uid-ravi", "2MEH", 5L) to 2L),
                ),
            ),
            key,
            self,
        )
        assertEquals("1GNH", result.ownStatus?.code)
        assertEquals(1, result.statuses.size)
        assertEquals("uid-ravi", result.statuses.single().uid)
    }

    // --- serverNow (A32) ---------------------------------------------------------------------------

    @Test
    fun `serverNow is parsed as the anchor every age is measured against`() {
        assertEquals(1_785_000_010_000L, GroupWire.parseSync(body(), key, self).serverNowMillis)
    }

    @Test
    fun `a relay that predates serverNow reports zero, so callers can fall back`() {
        // Zero must not be mistaken for the epoch — it is the signal to use the documented
        // device-clock fallback instead.
        assertEquals(0L, GroupWire.parseSync(body(serverNow = null), key, self).serverNowMillis)
    }

    // --- The encoder -------------------------------------------------------------------------------

    @Test
    fun `encoding omits stAge entirely when it is unknown`() {
        assertFalse("stAge" in GroupWire.encodeStatus("2MEH", null))
        assertTrue("stAge" in GroupWire.encodeStatus("2MEH", 0L))
    }

    @Test
    fun `a negative age from a broken caller clamps rather than travelling`() {
        val json = JSONObject(GroupWire.encodeStatus("2MEH", -5L))
        assertEquals(0L, json.getLong("stAge"))
    }

    @Test
    fun `the status payload stays far inside the envelope budget`() {
        // §4.3 budgets 256 chars for MAX_STATUS_ENVELOPE_CHARS; a sealed status is ~72.
        val sealed = statusEnvelope("uid-ravi", "2MEH:T15", 99_999L)
        assertTrue("sealed status was ${sealed.length} chars", sealed.length < 256)
    }
}
