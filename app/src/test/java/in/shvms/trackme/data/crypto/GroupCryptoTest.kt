package `in`.shvms.trackme.data.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Verifies [GroupCrypto] against the shared cross-platform fixture.
 *
 * SCOPE_1.7.0 §5.3: three implementations must agree byte-for-byte, and a mismatch presents as
 * *"the feature silently doesn't work"* — two users cannot see each other and nothing logs an
 * error. `app/src/test/resources/group-crypto-vectors.json` is copied verbatim from
 * `track-me-web/tests/fixtures/`, where it is generated; Node and the browser already pass it.
 *
 * The RFC 5869 cases run **first**, deliberately. There is no HKDF primitive below API 33 and
 * minSdk is 24, so `GroupCrypto.hkdf` is hand-rolled — validating it against the public standard
 * separates "our KDF is wrong" from "our caller is wrong", which are very different bugs.
 *
 * Deliberately a plain JVM test: no Robolectric, no Android imports. The fixture is parsed by a
 * few lines of regex rather than `org.json`, which under `isReturnDefaultValues = true` would
 * silently hand back empty values instead of failing — the exact class of silent wrongness this
 * whole file exists to prevent.
 */
class GroupCryptoTest {

    // --- Fixture loading ---------------------------------------------------------------------

    private val fixture: String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("group-crypto-vectors.json"),
    ) { "group-crypto-vectors.json is missing from test resources" }
        .bufferedReader().use { it.readText() }

    /**
     * One `{ … }` object per element of the named top-level array.
     *
     * Scans rather than pattern-matches, because it has to be string-aware: every `plaintext`
     * value in the fixture is itself JSON, so it contains braces. A naive `\{[^{}]*\}` matches
     * *inside* those strings and invents objects that do not exist.
     */
    private fun objectsIn(arrayName: String): List<Map<String, String>> {
        val start = fixture.indexOf("\"$arrayName\"")
        require(start >= 0) { "fixture has no \"$arrayName\" array" }

        val objects = mutableListOf<String>()
        var depth = 0
        var objStart = -1
        var inString = false
        var escaped = false

        var i = fixture.indexOf('[', start) + 1
        while (i < fixture.length) {
            val c = fixture[i]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> { if (depth == 0) objStart = i; depth++ }
                c == '}' -> {
                    depth--
                    if (depth == 0) objects.add(fixture.substring(objStart, i + 1))
                }
                c == ']' && depth == 0 -> break
            }
            i++
        }
        return objects.map(::fieldsOf)
    }

    private fun fieldsOf(obj: String): Map<String, String> =
        Regex("\"([A-Za-z0-9]+)\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|null)")
            .findAll(obj)
            .associate { m -> m.groupValues[1] to unescape(m.groupValues[3]) }

    /** JSON string unescaping, including the `\uXXXX` the unicode vector depends on. */
    private fun unescape(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\') { out.append(c); i++; continue }
            when (val esc = raw[i + 1]) {
                'n' -> { out.append('\n'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                'r' -> { out.append('\r'); i += 2 }
                'b' -> { out.append('\b'); i += 2 }
                'f' -> { out.append('\u000C'); i += 2 }
                'u' -> { out.append(raw.substring(i + 2, i + 6).toInt(16).toChar()); i += 6 }
                else -> { out.append(esc); i += 2 }
            }
        }
        return out.toString()
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String) = ByteArray(s.length / 2) {
        s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    private fun purposeFor(purpose: String, memberUid: String?): GroupCrypto.Purpose = when (purpose) {
        "meta" -> GroupCrypto.Purpose.Meta
        "code" -> GroupCrypto.Purpose.Code
        "roster" -> GroupCrypto.Purpose.Roster(requireNotNull(memberUid))
        "pos" -> GroupCrypto.Purpose.Position(requireNotNull(memberUid))
        else -> error("unknown purpose $purpose")
    }

    // --- HKDF against the public standard, before our own fixture -----------------------------

    @Test
    fun `hkdf matches RFC 5869 test case 1`() {
        // Exercises salt and info handling. We never pass a salt in production, but pinning the
        // full construction against the public standard is what separates "our hand-rolled KDF is
        // wrong" from "our caller is wrong" — two very different bugs, and only one of them is in
        // this file.
        val okm = GroupCrypto.hkdf(
            ikm = ByteArray(22) { 0x0b },
            info = unhex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
            salt = unhex("000102030405060708090a0b0c"),
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            hex(okm),
        )
    }

    @Test
    fun `hkdf matches RFC 5869 test case 3 - the empty-salt config we actually use`() {
        val okm = GroupCrypto.hkdf(ByteArray(22) { 0x0b }, "", 42)
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            hex(okm),
        )
    }

    @Test
    fun `hkdf expands past one hash block`() {
        // L = 42 needs two iterations of the expand loop; every production call asks for 32 and
        // would never exercise the counter increment.
        assertEquals(42, GroupCrypto.hkdf(ByteArray(22) { 0x0b }, "", 42).size)
        assertEquals(96, GroupCrypto.hkdf(ByteArray(22) { 0x0b }, "x", 96).size)
    }

    // --- base64url, both directions -----------------------------------------------------------

    @Test
    fun `base64url round-trips every payload length`() {
        // Hand-rolled because java.util.Base64 is API 26 and minSdk is 24. The tail cases (1 and
        // 2 leftover bytes) are where an unpadded encoder goes wrong.
        for (n in 0..64) {
            val bytes = ByteArray(n) { (it * 7 + 3).toByte() }
            val encoded = GroupCrypto.base64UrlEncode(bytes)
            assertTrue("padding leaked at n=$n", !encoded.contains('='))
            assertTrue("non-url alphabet at n=$n", encoded.all { it.isLetterOrDigit() || it == '-' || it == '_' })
            assertEquals("round trip failed at n=$n", hex(bytes), hex(GroupCrypto.base64UrlDecode(encoded)))
        }
    }

    @Test
    fun `base64url produces the url-safe alphabet, not the standard one`() {
        // 0xFB 0xFF encodes to "+/" in standard base64 and "-_" in base64url. A standard encoder
        // would pass a round-trip test and still break every other platform.
        assertEquals("-_8", GroupCrypto.base64UrlEncode(byteArrayOf(0xFB.toByte(), 0xFF.toByte())))
    }

    // --- The shared fixture ---------------------------------------------------------------------

    @Test
    fun `fixture reproduces every token hash, key, and envelope`() {
        val cases = objectsIn("cases")
        assertTrue("fixture has no cases", cases.isNotEmpty())

        for (c in cases) {
            val name = c["name"]!!
            val token = c["token"]!!
            val purpose = purposeFor(c["purpose"]!!, c["memberUid"])

            assertEquals("$name: token hash", c["tokenHashHex"], GroupCrypto.groupTokenHash(token))
            assertEquals("$name: derived key", c["keyHex"], hex(GroupCrypto.deriveGroupKey(token)))
            assertEquals("$name: context string", c["context"], purpose.context)

            val key = unhex(c["keyHex"]!!)
            val nonce = GroupCrypto.base64UrlDecode(c["nonceB64Url"]!!)
            assertEquals(
                "$name: seal must reproduce the envelope byte-for-byte",
                c["envelope"],
                GroupCrypto.seal(key, c["plaintext"]!!, purpose, nonce),
            )
            assertEquals(
                "$name: open must recover the plaintext",
                c["plaintext"],
                GroupCrypto.open(key, c["envelope"]!!, purpose),
            )
        }
    }

    @Test
    fun `fixture reproduces the join-code wrapped tokens`() {
        val cases = objectsIn("codeCases")
        assertTrue("fixture has no codeCases", cases.isNotEmpty())

        for (c in cases) {
            val name = c["name"]!!
            val code = c["joinCode"]!!
            assertEquals("$name: code key", c["codeKeyHex"], hex(GroupCrypto.deriveCodeKey(code)))
            assertEquals("$name: context", c["context"], GroupCrypto.Purpose.Code.context)

            val nonce = GroupCrypto.base64UrlDecode(c["nonceB64Url"]!!)
            assertEquals(
                "$name: wrapper must match byte-for-byte",
                c["wrappedToken"],
                GroupCrypto.seal(unhex(c["codeKeyHex"]!!), c["inviteToken"]!!, GroupCrypto.Purpose.Code, nonce),
            )
            assertEquals(
                "$name: unwrap must recover the invite token",
                c["inviteToken"],
                GroupCrypto.unwrapTokenWithCode(code, c["wrappedToken"]!!),
            )
        }
    }

    @Test
    fun `the unicode vector survives Kotlin's UTF-8 handling`() {
        // The likeliest place a third implementation diverges, and it fails silently: a wrong byte
        // count still decodes to *something* on the platform that sealed it. 7 locales ship.
        val c = objectsIn("cases").first { it["name"]!!.contains("unicode") }
        val plain = GroupCrypto.open(unhex(c["keyHex"]!!), c["envelope"]!!, GroupCrypto.Purpose.Meta)
        assertEquals(c["plaintext"], plain)
        assertTrue("emoji did not survive", plain.contains("🚴"))
        assertTrue("devanagari did not survive", plain.contains("सवारी"))
    }

    // --- Properties the fixture cannot express ---------------------------------------------------

    @Test
    fun `a fresh token round-trips`() {
        val token = GroupCrypto.generateInviteToken()
        val key = GroupCrypto.deriveGroupKey(token)
        val plaintext = """{"lat":12.9716,"lng":77.5946}"""
        val purpose = GroupCrypto.Purpose.Position("uid-a")
        assertEquals(plaintext, GroupCrypto.open(key, GroupCrypto.seal(key, plaintext, purpose), purpose))
    }

    @Test
    fun `generated tokens are 22 base64url characters and unique`() {
        val seen = mutableSetOf<String>()
        repeat(500) {
            val token = GroupCrypto.generateInviteToken()
            assertTrue("bad token shape: $token", Regex("^[A-Za-z0-9_-]{22}$").matches(token))
            assertTrue("generateInviteToken repeated a value", seen.add(token))
        }
    }

    @Test
    fun `sealing twice never reuses a nonce`() {
        // Repeating a (key, nonce) pair under GCM leaks the XOR of both plaintexts. Positions are
        // overwritten every ~10s for hours, so this path is hot.
        val key = GroupCrypto.deriveGroupKey(GroupCrypto.generateInviteToken())
        val nonces = (1..500).map { GroupCrypto.seal(key, "x", GroupCrypto.Purpose.Meta).split('.')[1] }
        assertEquals("nonce collision", 500, nonces.toSet().size)
    }

    @Test
    fun `join codes use the Crockford alphabet and exclude I L O U`() {
        val seen = StringBuilder()
        repeat(2000) { seen.append(GroupCrypto.generateJoinCode()) }
        for (c in "ILOU") {
            assertTrue("alphabet leaked '$c'", !seen.contains(c))
        }
        assertTrue("bad code shape", Regex("^[0-9ABCDEFGHJKMNPQRSTVWXYZ]+$").matches(seen.toString()))
    }

    @Test
    fun `join codes are evenly spread across the alphabet`() {
        // A `% 32` on a raw byte would over-represent the first eight symbols by 25%. Under the
        // wrapped-token design a biased code is weaker key material, not just an uglier code.
        val counts = mutableMapOf<Char, Int>()
        repeat(4000) { GroupCrypto.generateJoinCode().forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        assertEquals("not every symbol was produced", 32, counts.size)
        assertTrue("distribution too skewed", counts.values.max().toDouble() / counts.values.min() < 1.5)
    }

    @Test
    fun `a generated code survives its own normalizer unchanged`() {
        // If it did not, the creator and the joiner would derive different code keys from the same
        // six characters — silent, and unfixable from the outside.
        repeat(500) {
            val code = GroupCrypto.generateJoinCode()
            assertEquals(code, GroupCrypto.normalizeJoinCode(code))
        }
    }

    @Test
    fun `normalizeJoinCode canonicalises what a human types`() {
        for (raw in listOf("abc123", "ABC 123", "ABC-123", " abc-1 23 ")) {
            assertEquals("failed on '$raw'", "ABC123", GroupCrypto.normalizeJoinCode(raw))
        }
        assertEquals("1BC123", GroupCrypto.normalizeJoinCode("IBC123"))
        assertEquals("1BC123", GroupCrypto.normalizeJoinCode("lBC123"))
        assertEquals("0BC123", GroupCrypto.normalizeJoinCode("OBC123"))
    }

    @Test
    fun `normalizeJoinCode rejects anything that is not a code`() {
        for (bad in listOf("", "ABC12", "ABC1234", "ABC12!", "UUUUUU", null)) {
            assertNull("accepted '$bad'", GroupCrypto.normalizeJoinCode(bad))
        }
    }

    @Test
    fun `a typed code that normalises to the creator code derives the same key`() {
        val expected = hex(GroupCrypto.deriveCodeKey("ABC123"))
        for (typed in listOf("abc123", "ABC-123", "abc 123")) {
            assertEquals(typed, expected, hex(GroupCrypto.deriveCodeKey(GroupCrypto.normalizeJoinCode(typed)!!)))
        }
    }

    @Test
    fun `the code key and the group key are never the same`() {
        // Domain separation via the HKDF info string. If these collided, knowing a join code would
        // hand you the group key directly rather than via the wrapper.
        assertNotEquals(
            hex(GroupCrypto.hkdf("ABC123".toByteArray(), GroupCrypto.HKDF_INFO, 32)),
            hex(GroupCrypto.deriveCodeKey("ABC123")),
        )
    }

    // --- Negative cases: each is an attack the relay could mount ---------------------------------

    private fun expectEnvelopeFailure(what: String, block: () -> Unit) {
        try {
            block()
            fail("$what should have thrown")
        } catch (e: GroupCrypto.EnvelopeException) {
            // expected
        }
    }

    @Test
    fun `a different token cannot open the envelope`() {
        val key = GroupCrypto.deriveGroupKey(GroupCrypto.generateInviteToken())
        val envelope = GroupCrypto.seal(key, "secret", GroupCrypto.Purpose.Meta)
        val other = GroupCrypto.deriveGroupKey(GroupCrypto.generateInviteToken())
        expectEnvelopeFailure("wrong key") { GroupCrypto.open(other, envelope, GroupCrypto.Purpose.Meta) }
    }

    @Test
    fun `a position envelope cannot be replayed into another member slot`() {
        // The relay holds every member's ciphertext and could swap Alice's into Bob's hash field.
        // The per-member AAD is what makes that fail instead of silently teleporting Bob.
        val key = GroupCrypto.deriveGroupKey(GroupCrypto.generateInviteToken())
        val alice = GroupCrypto.seal(key, """{"lat":1}""", GroupCrypto.Purpose.Position("uid-alice"))
        expectEnvelopeFailure("replay into another slot") {
            GroupCrypto.open(key, alice, GroupCrypto.Purpose.Position("uid-bob"))
        }
    }

    @Test
    fun `a roster envelope cannot be opened as a position envelope`() {
        val key = GroupCrypto.deriveGroupKey(GroupCrypto.generateInviteToken())
        val roster = GroupCrypto.seal(key, """{"displayName":"A"}""", GroupCrypto.Purpose.Roster("uid-a"))
        expectEnvelopeFailure("purpose confusion") {
            GroupCrypto.open(key, roster, GroupCrypto.Purpose.Position("uid-a"))
        }
    }

    @Test
    fun `tampering with the ciphertext or the nonce is detected`() {
        val key = GroupCrypto.deriveGroupKey(GroupCrypto.generateInviteToken())
        val parts = GroupCrypto.seal(key, "secret", GroupCrypto.Purpose.Meta).split('.')

        val body = GroupCrypto.base64UrlDecode(parts[2]).also { it[0] = (it[0].toInt() xor 1).toByte() }
        expectEnvelopeFailure("tampered ciphertext") {
            GroupCrypto.open(key, "${parts[0]}.${parts[1]}.${GroupCrypto.base64UrlEncode(body)}", GroupCrypto.Purpose.Meta)
        }

        val nonce = GroupCrypto.base64UrlDecode(parts[1]).also { it[0] = (it[0].toInt() xor 1).toByte() }
        expectEnvelopeFailure("tampered nonce") {
            GroupCrypto.open(key, "${parts[0]}.${GroupCrypto.base64UrlEncode(nonce)}.${parts[2]}", GroupCrypto.Purpose.Meta)
        }
    }

    @Test
    fun `an unknown envelope version is rejected rather than guessed`() {
        val key = GroupCrypto.deriveGroupKey(GroupCrypto.generateInviteToken())
        val parts = GroupCrypto.seal(key, "secret", GroupCrypto.Purpose.Meta).split('.')
        expectEnvelopeFailure("v2 envelope") {
            GroupCrypto.open(key, "v2.${parts[1]}.${parts[2]}", GroupCrypto.Purpose.Meta)
        }
    }

    @Test
    fun `malformed envelopes throw EnvelopeException, never a raw crypto error`() {
        val key = GroupCrypto.deriveGroupKey(GroupCrypto.generateInviteToken())
        for (bad in listOf("", "v1", "v1.abc", "v1.a.b.c", "v1..", "v1.AAECAwQFBgcICQoL.AA")) {
            expectEnvelopeFailure("malformed '$bad'") { GroupCrypto.open(key, bad, GroupCrypto.Purpose.Meta) }
        }
    }

    @Test
    fun `failure messages do not distinguish wrong key from wrong context`() {
        // Telling an attacker which half they got right is free information.
        val key = GroupCrypto.deriveGroupKey(GroupCrypto.generateInviteToken())
        val envelope = GroupCrypto.seal(key, "secret", GroupCrypto.Purpose.Position("uid-a"))
        val wrongKey = runCatching {
            GroupCrypto.open(GroupCrypto.deriveGroupKey(GroupCrypto.generateInviteToken()), envelope, GroupCrypto.Purpose.Position("uid-a"))
        }.exceptionOrNull()?.message
        val wrongContext = runCatching {
            GroupCrypto.open(key, envelope, GroupCrypto.Purpose.Position("uid-b"))
        }.exceptionOrNull()?.message
        assertEquals(wrongKey, wrongContext)
    }

    @Test
    fun `the wrong code cannot unwrap the token`() {
        val wrapped = GroupCrypto.wrapTokenForCode("ABC123", GroupCrypto.generateInviteToken())
        expectEnvelopeFailure("wrong code") { GroupCrypto.unwrapTokenWithCode("ABC124", wrapped) }
    }

    @Test
    fun `unwrapping rejects a payload that authenticates but is not a token`() {
        // Guards the format drifting: junk fed into deriveGroupKey produces a blank map with no
        // error rather than a clear failure.
        val notAToken = GroupCrypto.seal(GroupCrypto.deriveCodeKey("ABC123"), "hello", GroupCrypto.Purpose.Code)
        expectEnvelopeFailure("non-token payload") { GroupCrypto.unwrapTokenWithCode("ABC123", notAToken) }
    }

    @Test
    fun `deriveCodeKey refuses anything that is not an already-normalised code`() {
        for (bad in listOf("abc123", "ABC-123", "ABC12", "ABCI23", "ABCO23", "ABCU23", "", "ABC 123")) {
            expectEnvelopeFailure("un-normalised '$bad'") { GroupCrypto.deriveCodeKey(bad) }
        }
    }

    @Test
    fun `token validation rejects wrong shapes`() {
        for (bad in listOf("", "short", "Zm9vYmFyYmF6cXV4MTIzNA==", "Zm9vYmFyYmF6cXV4MTIzN\$")) {
            expectEnvelopeFailure("token '$bad'") { GroupCrypto.deriveGroupKey(bad) }
            expectEnvelopeFailure("token '$bad'") { GroupCrypto.groupTokenHash(bad) }
        }
    }

    @Test
    fun `keys of the wrong length are refused`() {
        expectEnvelopeFailure("short key") {
            GroupCrypto.seal(ByteArray(16), "x", GroupCrypto.Purpose.Meta)
        }
    }
}
