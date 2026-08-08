package `in`.shvms.trackme.data.remote

import `in`.shvms.trackme.data.crypto.GroupCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.json.JSONObject

/**
 * The hot-path parser — SCOPE_1.7.0 §6.2 H9: *"a malformed parse on the hot path is a crash
 * risk."* Sync runs every ~10s for hours, so one unguarded `getString` on a field the relay
 * omitted takes down the map mid-ride.
 *
 * Runs against the **real** `org.json` (a test-only dependency), not android.jar's stub: this
 * module sets `isReturnDefaultValues = true`, so the stub would return empty values instead of
 * throwing and every assertion here would pass while parsing nothing.
 *
 * Payloads are built with the real [GroupCrypto], so this doubles as an end-to-end check that what
 * Android seals is what Android reads — and the shared fixture already proved the other two
 * platforms agree on that format.
 */
class GroupWireTest {

    private val token = "Zm9vYmFyYmF6cXV4MTIzNA"
    private val key = GroupCrypto.deriveGroupKey(token)
    private val self = "uid-me"

    private fun positionEnvelope(uid: String, lat: Double, lng: Double): String =
        GroupCrypto.seal(
            key,
            GroupWire.encodePosition(lat, lng, 4.2f, 137f, 82, true),
            GroupCrypto.Purpose.Position(uid),
        )

    private fun syncBody(
        positions: Map<String, Pair<String, Long>>,
        roster: Map<String, String>? = null,
        meta: String? = null,
        state: String = "LIVE",
        nextSyncInSec: Int? = 10,
    ): String = JSONObject().apply {
        put("state", state)
        put("expiresAt", 1785014400000L)
        put("rev", 7)
        put("maxMembers", 5)
        nextSyncInSec?.let { put("nextSyncInSec", it) }
        put("positions", JSONObject().apply {
            positions.forEach { (uid, pair) ->
                put(uid, JSONObject().put("e", pair.first).put("ts", pair.second))
            }
        })
        roster?.let { put("roster", JSONObject(it as Map<*, *>)) }
        meta?.let { put("meta", it) }
    }.toString()

    // --- The happy path -------------------------------------------------------------------------

    @Test
    fun `sync decrypts every member position`() {
        val body = syncBody(
            mapOf(
                "uid-alice" to (positionEnvelope("uid-alice", 12.9716, 77.5946) to 1785000000000L),
                "uid-bob" to (positionEnvelope("uid-bob", 12.98, 77.6) to 1785000001000L),
            ),
        )
        val result = GroupWire.parseSync(body, key, self)

        assertEquals(2, result.positions.size)
        val alice = result.positions.first { it.uid == "uid-alice" }
        assertEquals(12.9716, alice.lat, 1e-9)
        assertEquals(77.5946, alice.lng, 1e-9)
        assertEquals(4.2f, alice.speedMps!!, 1e-4f)
        assertEquals(137f, alice.headingDeg!!, 1e-4f)
        assertEquals(82, alice.batteryPercent)
        assertTrue(alice.moving)
        assertEquals(1785000000000L, alice.serverTsMillis)
        assertEquals(10, result.nextSyncInSec)
        assertTrue("nothing should have failed to decrypt", result.undecryptable.isEmpty())
    }

    @Test
    fun `the caller's own position is filtered out`() {
        // §4.1: the relay returns it deliberately so a client can confirm its own push landed
        // (§15.4 — the relay is opaque to us, so this is the only self-diagnostic there is), and
        // the client drops it before rendering. We never draw ourselves twice (§2.6).
        val body = syncBody(
            mapOf(
                self to (positionEnvelope(self, 1.0, 2.0) to 1L),
                "uid-alice" to (positionEnvelope("uid-alice", 3.0, 4.0) to 2L),
            ),
        )
        val result = GroupWire.parseSync(body, key, self)
        assertEquals(1, result.positions.size)
        assertEquals("uid-alice", result.positions.single().uid)
    }

    @Test
    fun `the server timestamp is used, never a device clock`() {
        // §4.4 and §8: staleness is computed from the relay's stamp so a skewed device clock
        // cannot poison freshness for the whole group.
        val encoded = GroupWire.encodePosition(1.0, 2.0, null, null, null, false)
        assertTrue("client must not send a timestamp", !encoded.contains("ts"))
        assertTrue(!encoded.contains("timestamp"))
    }

    // --- §8: one bad member must never take down the map ----------------------------------------

    @Test
    fun `a member whose envelope will not open is skipped, not thrown`() {
        val body = syncBody(
            mapOf(
                "uid-alice" to (positionEnvelope("uid-alice", 1.0, 2.0) to 1L),
                "uid-broken" to ("v1.AAECAwQFBgcICQoL.AAAAAAAAAAAAAAAAAAAAAAAA" to 2L),
            ),
        )
        val result = GroupWire.parseSync(body, key, self)
        assertEquals("the readable member was lost too", 1, result.positions.size)
        assertEquals(listOf("uid-broken"), result.undecryptable)
    }

    @Test
    fun `a position replayed into another member's slot is rejected, not rendered`() {
        // The relay could copy Alice's ciphertext into Bob's field. Without the per-member AAD,
        // Bob's marker would silently teleport to Alice — a decryption that succeeds and lies.
        val alice = positionEnvelope("uid-alice", 12.9716, 77.5946)
        val body = syncBody(mapOf("uid-bob" to (alice to 1L)))
        val result = GroupWire.parseSync(body, key, self)
        assertTrue("a swapped envelope was accepted", result.positions.isEmpty())
        assertEquals(listOf("uid-bob"), result.undecryptable)
    }

    @Test
    fun `a group whose name will not decrypt is still usable`() {
        val body = syncBody(
            mapOf("uid-alice" to (positionEnvelope("uid-alice", 1.0, 2.0) to 1L)),
            meta = "v1.AAECAwQFBgcICQoL.AAAAAAAAAAAAAAAAAAAAAAAA",
        )
        val result = GroupWire.parseSync(body, key, self)
        assertNull(result.meta)
        assertEquals("losing the name must not lose the session", 1, result.positions.size)
    }

    // --- Missing and malformed fields -----------------------------------------------------------

    @Test
    fun `a sync response with no positions parses to an empty list`() {
        val result = GroupWire.parseSync(syncBody(emptyMap()), key, self)
        assertTrue(result.positions.isEmpty())
        assertEquals("LIVE", result.state)
    }

    @Test
    fun `a sync response missing optional fields uses defaults instead of crashing`() {
        // The relay omits `roster` on most syncs (rev-gated) and could omit others. None of that
        // may throw on a path that runs every ten seconds for hours.
        val result = GroupWire.parseSync("""{"state":"LIVE"}""", key, self)
        assertEquals("LIVE", result.state)
        assertNull("roster must be absent, not empty", result.roster)
        assertEquals(GroupBackoff.DEFAULT_SYNC_INTERVAL_SEC, result.nextSyncInSec)
        assertTrue(result.positions.isEmpty())
    }

    @Test
    fun `a completely malformed body raises WireException, not a raw JSON error`() {
        for (bad in listOf("", "not json", "[]", "{", "null")) {
            try {
                GroupWire.parseSync(bad, key, self)
                if (bad != "null" && bad != "[]") fail("accepted '$bad'")
            } catch (e: GroupWire.WireException) {
                // expected
            } catch (e: Exception) {
                fail("'$bad' raised ${e.javaClass.simpleName} instead of WireException")
            }
        }
    }

    @Test
    fun `create and join responses missing a required field fail loudly`() {
        // These run once, at a point where a silent default would leave the user in a group the
        // app cannot address. Unlike sync, they should throw.
        try {
            GroupWire.parseCreate("""{"joinCode":"ABC123","expiresAt":1}""")
            fail("accepted a create response with no groupId")
        } catch (e: GroupWire.WireException) {
            assertTrue(e.message!!.contains("groupId"))
        }
        try {
            GroupWire.parseJoin("""{"state":"LIVE"}""", key)
            fail("accepted a join response with no groupId")
        } catch (e: GroupWire.WireException) {
            // expected
        }
    }

    // --- Roster ----------------------------------------------------------------------------------

    @Test
    fun `roster entries decrypt and a broken one does not take the rest with it`() {
        val good = GroupCrypto.seal(
            key,
            GroupWire.encodeRoster("Alice Kaur", "AK", null),
            GroupCrypto.Purpose.Roster("uid-alice"),
        )
        val body = syncBody(
            emptyMap(),
            roster = mapOf(
                "uid-alice" to good,
                "uid-broken" to "v1.AAECAwQFBgcICQoL.AAAAAAAAAAAAAAAAAAAAAAAA",
            ),
        )
        val result = GroupWire.parseSync(body, key, self)
        assertEquals(1, result.roster!!.size)
        assertEquals("Alice Kaur", result.roster!!.single().displayName)
        assertEquals("AK", result.roster!!.single().initials)
        assertNull(result.roster!!.single().photoUrl)
        assertTrue(result.undecryptable.contains("uid-broken"))
    }

    // --- Encoders ----------------------------------------------------------------------------------

    @Test
    fun `optional position fields are omitted rather than sent as null`() {
        val encoded = GroupWire.encodePosition(1.0, 2.0, null, null, null, false)
        val json = JSONObject(encoded)
        assertTrue(json.has("lat"))
        assertTrue(json.has("lng"))
        assertTrue("speed should be omitted", !json.has("spd"))
        assertTrue("heading should be omitted", !json.has("hdg"))
        assertTrue("battery should be omitted", !json.has("bat"))
        assertEquals(false, json.getBoolean("moving"))
    }

    @Test
    fun `a position round-trips through seal and parse unchanged`() {
        val body = syncBody(mapOf("uid-a" to (positionEnvelope("uid-a", -33.8688, 151.2093) to 99L)))
        val parsed = GroupWire.parseSync(body, key, self).positions.single()
        assertEquals(-33.8688, parsed.lat, 1e-9)
        assertEquals(151.2093, parsed.lng, 1e-9)
    }

    @Test
    fun `the relay's error code is extracted when present`() {
        assertEquals("REDIS_UNAVAILABLE", GroupWire.errorCode("""{"code":"REDIS_UNAVAILABLE"}"""))
        assertEquals("GROUP_FULL", GroupWire.errorCode("""{"error":"full","code":"GROUP_FULL"}"""))
        assertNull(GroupWire.errorCode("""{"error":"nope"}"""))
        assertNull(GroupWire.errorCode("not json"))
        assertNull(GroupWire.errorCode(null))
    }

    @Test
    fun `resolve exposes the wrapped token only when the relay sent one`() {
        val withCode = GroupWire.parseResolve(
            """{"groupId":"g","state":"LIVE","memberCount":3,"maxMembers":5,"expiresAt":1,"wrappedToken":"v1.a.b"}""",
        )
        assertEquals("v1.a.b", withCode.wrappedToken)
        val withToken = GroupWire.parseResolve(
            """{"groupId":"g","state":"LIVE","memberCount":3,"maxMembers":5,"expiresAt":1}""",
        )
        assertNull(withToken.wrappedToken)
        assertEquals(3, withToken.memberCount)
    }

    // --- Initials (sealed into the roster, so all clients must agree) -----------------------------

    @Test
    fun `initials take the first letter of the first and last word`() {
        assertEquals("AK", GroupSessionManager.initialsOf("Alice Kaur"))
        assertEquals("PS", GroupSessionManager.initialsOf("Priya Sharma"))
        assertEquals("R", GroupSessionManager.initialsOf("Ravi"))
        // §3.3 is "first letter of first + LAST word", so the middle word is skipped entirely.
        assertEquals("JV", GroupSessionManager.initialsOf("Jean  de  Ville"))
        assertEquals("R", GroupSessionManager.initialsOf("  ravi  "))
    }

    @Test
    fun `a member with no display name gets no initials rather than an empty circle`() {
        // §8: "Member has no photo AND no display name — fall back to a neutral glyph plus a
        // stable per-member colour. Never render a blank circle or 'null'."
        assertNull(GroupSessionManager.initialsOf(null))
        assertNull(GroupSessionManager.initialsOf(""))
        assertNull(GroupSessionManager.initialsOf("   "))
    }

    @Test
    fun `an empty roster envelope carries no null strings`() {
        val json = JSONObject(GroupWire.encodeRoster(null, null, null))
        assertEquals("empty roster should be an empty object", 0, json.length())
        assertNotNull(GroupWire.encodeRoster("A", "A", null))
    }
}
