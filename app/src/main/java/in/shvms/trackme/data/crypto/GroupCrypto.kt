package `in`.shvms.trackme.data.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Group Ride envelope crypto — Android implementation.
 *
 * The third implementation of the contract, after `lib/group/crypto.ts` (Node) and
 * `public/js/group-crypto.mjs` (browser). The binding definition is
 * `track-me-web/doc/group-crypto-contract.md`; the shared fixture at
 * `app/src/test/resources/group-crypto-vectors.json` is what proves the three agree.
 *
 * SCOPE_1.7.0 §5.3 names a byte-for-byte mismatch as the crypto design's main risk, and it fails
 * *silently* — two users simply cannot see each other, and nothing logs an error. So every
 * primitive here is pinned by `GroupCryptoTest`, and that fixture must be re-run on all three
 * platforms whenever the format changes.
 *
 * **Pure by design.** No Android imports, no context, no `android.util.Base64` — so the whole
 * file runs in a plain JVM unit test, which is what stops it rotting (§2.9). §4.6 requires this.
 *
 * **minSdk 24 constraints, all deliberate:**
 * - No `javax.crypto.KDF` and no `HKDF` primitive below API 33, so extract/expand is hand-rolled
 *   over `Mac("HmacSHA256")`. It is validated against RFC 5869 test cases 1 and 3 *before* it is
 *   ever pointed at our own fixture — if it passes the public standard and still disagrees with
 *   us, the bug is in the caller, not the KDF.
 * - Base64url is hand-rolled rather than using `java.util.Base64` (API 26) or
 *   `android.util.Base64` (an Android dependency that would make this untestable off-device).
 *   Roughly thirty lines against a crash on API 24–25 in a security path.
 */
object GroupCrypto {

    /** Envelope format version. Readers reject anything else rather than guessing. */
    const val ENVELOPE_VERSION = "v1"

    /** HKDF `info` for the group key, derived from the invite token. */
    const val HKDF_INFO = "trackme:group:v1"

    /**
     * HKDF `info` for the code key, derived from the join code. Distinct from [HKDF_INFO] so the
     * same string could never produce both keys.
     */
    const val HKDF_CODE_INFO = "trackme:group-code:v1"

    const val TOKEN_BYTES = 16
    const val KEY_LENGTH_BYTES = 32
    const val NONCE_LENGTH_BYTES = 12
    const val TAG_LENGTH_BYTES = 16

    /**
     * Crockford base32: no I, L, O or U. 32^6 ≈ 1.07e9, and the four excluded letters are exactly
     * the ones people mistype as 1/0 when reading a code aloud.
     */
    const val CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val JOIN_CODE_LENGTH = 6

    private val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{22}$")
    private val JOIN_CODE_PATTERN = Regex("^[0-9ABCDEFGHJKMNPQRSTVWXYZ]{6}$")
    private val random = SecureRandom()

    /**
     * What an envelope is authenticated against. Not encrypted and not transmitted — the reader
     * rebuilds it from where the envelope was found.
     *
     * This is what stops an untrusted relay moving ciphertext between slots: without it, the
     * server could copy Alice's position into Bob's hash field and Bob's marker would silently
     * teleport to Alice — a decryption that *succeeds* and produces a lie.
     */
    sealed class Purpose(val context: String) {
        object Meta : Purpose("$ENVELOPE_VERSION:meta")
        object Code : Purpose("$ENVELOPE_VERSION:code")
        class Roster(uid: String) : Purpose("$ENVELOPE_VERSION:roster:$uid")
        class Position(uid: String) : Purpose("$ENVELOPE_VERSION:pos:$uid")
    }

    class EnvelopeException(message: String) : Exception(message)

    // --- Tokens ---------------------------------------------------------------------------------

    /**
     * A fresh invite token. **The client mints this, never the server** — a relay that generated
     * the token would hold the key to everything it stores, which voids the entire §5.3 claim.
     */
    fun generateInviteToken(): String = base64UrlEncode(ByteArray(TOKEN_BYTES).also(random::nextBytes))

    /**
     * The relay's lookup key: lowercase hex SHA-256 over the token's **ASCII bytes**, not over
     * decoded entropy. One canonical form, no decode step, nothing for a port to get subtly wrong.
     */
    fun groupTokenHash(token: String): String {
        requireToken(token)
        return MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    // --- Join codes -----------------------------------------------------------------------------

    /**
     * A fresh join code. Rejection-sampled so every symbol is equally likely — `% 32` on a raw
     * byte would over-represent the first eight by 25%, and under the wrapped-token design a
     * biased code is weaker key material, not just an uglier code.
     *
     * **The client mints this too**, because the wrapper in [wrapTokenForCode] is keyed on it and
     * the server cannot invent a code the client has already wrapped a token under.
     */
    fun generateJoinCode(): String {
        val limit = 256 - (256 % CROCKFORD_ALPHABET.length)
        val out = StringBuilder(JOIN_CODE_LENGTH)
        val buffer = ByteArray(JOIN_CODE_LENGTH)
        while (out.length < JOIN_CODE_LENGTH) {
            random.nextBytes(buffer)
            for (b in buffer) {
                val v = b.toInt() and 0xFF
                if (v < limit) {
                    out.append(CROCKFORD_ALPHABET[v % CROCKFORD_ALPHABET.length])
                    if (out.length == JOIN_CODE_LENGTH) break
                }
            }
        }
        return out.toString()
    }

    /**
     * Canonicalises what a human actually types — case, spaces, dashes, and the confusable letters
     * the alphabet omits. Returns null if it still is not a code.
     *
     * **Contract-critical.** The normalised form is both the Redis key and the HKDF input, so
     * skipping this derives a different key from the same six characters and decrypts nothing,
     * with no error anywhere.
     */
    fun normalizeJoinCode(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw.uppercase()
            .replace(Regex("[\\s-]"), "")
            .replace('I', '1').replace('L', '1')
            .replace('O', '0')
        return if (JOIN_CODE_PATTERN.matches(cleaned)) cleaned else null
    }

    // --- Key derivation -------------------------------------------------------------------------

    /** `HKDF-SHA256(utf8(token), salt = empty, info = HKDF_INFO, L = 32)`. */
    fun deriveGroupKey(token: String): ByteArray {
        requireToken(token)
        return hkdf(token.toByteArray(Charsets.UTF_8), HKDF_INFO, KEY_LENGTH_BYTES)
    }

    /**
     * `HKDF-SHA256(utf8(normalisedJoinCode), salt = empty, info = HKDF_CODE_INFO, L = 32)`.
     *
     * Takes the **normalised** code. Passing raw user input here is the silent-failure case above.
     */
    fun deriveCodeKey(joinCode: String): ByteArray {
        if (!JOIN_CODE_PATTERN.matches(joinCode)) {
            throw EnvelopeException("join code must be 6 normalised Crockford base32 characters")
        }
        return hkdf(joinCode.toByteArray(Charsets.UTF_8), HKDF_CODE_INFO, KEY_LENGTH_BYTES)
    }

    /**
     * RFC 5869 HKDF over HMAC-SHA256.
     *
     * There is no HKDF primitive below API 33 and minSdk is 24, so this is the extract-then-expand
     * construction written out. An absent salt is `HashLen` zero bytes per RFC 5869 §2.2 — not an
     * empty key, which `Mac.init` rejects outright.
     *
     * The expand loop is general even though every caller asks for exactly one block, because the
     * RFC's own test vectors use L = 42 and validating against them is the point.
     */
    internal fun hkdf(ikm: ByteArray, info: String, length: Int): ByteArray =
        hkdf(ikm, info.toByteArray(Charsets.UTF_8), length, ByteArray(0))

    /**
     * The full construction, salt included.
     *
     * Production never passes a salt — the contract fixes it as empty, because the IKM is already
     * 128 uniform bits and the key must be derivable before a groupId exists. The parameter exists
     * so `GroupCryptoTest` can pin this against RFC 5869 test case 1, which does use one. Checking
     * a hand-rolled crypto primitive against the whole public standard rather than only the corner
     * of it we happen to use is worth one parameter.
     */
    internal fun hkdf(ikm: ByteArray, info: ByteArray, length: Int, salt: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val hashLen = mac.macLength

        // Extract: PRK = HMAC(salt, IKM). The salt is the key, the IKM is the message. An absent
        // salt is HashLen zero bytes per RFC 5869 §2.2 — not an empty key, which Mac.init rejects.
        val saltKey = if (salt.isEmpty()) ByteArray(hashLen) else salt
        mac.init(SecretKeySpec(saltKey, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        // Expand: T(n) = HMAC(PRK, T(n-1) | info | n), OKM = T(1) | T(2) | … truncated to length.
        val infoBytes = info
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        while (written < length) {
            mac.update(previous)
            mac.update(infoBytes)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val take = minOf(previous.size, length - written)
            previous.copyInto(out, written, 0, take)
            written += take
            counter++
        }
        return out
    }

    // --- Envelopes ------------------------------------------------------------------------------

    /**
     * `v1.<base64url nonce>.<base64url ciphertext||tag>`
     *
     * `nonce` is a parameter only so the fixture can pin it. **Production callers must omit it** —
     * repeating a (key, nonce) pair under GCM leaks the XOR of both plaintexts and destroys the
     * authentication guarantee, and positions are overwritten every ~10s for hours.
     */
    @JvmOverloads
    fun seal(
        key: ByteArray,
        plaintext: String,
        purpose: Purpose,
        nonce: ByteArray = ByteArray(NONCE_LENGTH_BYTES).also(random::nextBytes),
    ): String {
        requireKey(key)
        if (nonce.size != NONCE_LENGTH_BYTES) {
            throw EnvelopeException("nonce must be $NONCE_LENGTH_BYTES bytes, got ${nonce.size}")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_LENGTH_BYTES * 8, nonce),
        )
        cipher.updateAAD(purpose.context.toByteArray(Charsets.UTF_8))
        // Java's GCM already appends the tag, which is the layout the contract specifies — no
        // platform has to splice it on by hand.
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return "$ENVELOPE_VERSION.${base64UrlEncode(nonce)}.${base64UrlEncode(body)}"
    }

    /**
     * Inverse of [seal].
     *
     * Every failure — malformed, unknown version, wrong key, wrong context, tampered — raises the
     * same opaque message. Distinguishing them would tell an attacker which half they got right.
     *
     * On the map path a failure means *skip that member and log*; it must never fail the whole
     * render (§8, "Decryption failure on a member's envelope").
     */
    fun open(key: ByteArray, envelope: String, purpose: Purpose): String {
        requireKey(key)
        val parts = envelope.split('.')
        if (parts.size != 3) throw EnvelopeException("malformed envelope")
        if (parts[0] != ENVELOPE_VERSION) throw EnvelopeException("unsupported envelope version")

        val nonce: ByteArray
        val body: ByteArray
        try {
            nonce = base64UrlDecode(parts[1])
            body = base64UrlDecode(parts[2])
        } catch (e: IllegalArgumentException) {
            throw EnvelopeException("malformed envelope")
        }
        if (nonce.size != NONCE_LENGTH_BYTES || body.size < TAG_LENGTH_BYTES) {
            throw EnvelopeException("malformed envelope")
        }

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_LENGTH_BYTES * 8, nonce),
            )
            cipher.updateAAD(purpose.context.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            throw EnvelopeException("envelope authentication failed")
        }
    }

    // --- Join-code token wrapping -----------------------------------------------------------------

    /**
     * Seals the invite token under a key derived from the join code, so a code-joiner can recover
     * it without the relay ever holding key material.
     *
     * This is what makes join-by-code compatible with §5.3 at all: §2.4 requires the code to work
     * as a standalone path and §15.1 defers join-by-link to 1.7.1, but the code is not the group
     * key — so a code-joiner would otherwise be authorised by the relay and able to decrypt
     * nothing.
     *
     * The honest consequence: **the join code is a security boundary.** What makes 30 bits
     * acceptable is that there is no offline attack — a wrapper cannot be obtained without already
     * knowing its code — so the only route is `resolve?c=`, rate-limited to 5/min, against a code
     * that dies in 30 minutes.
     */
    fun wrapTokenForCode(joinCode: String, token: String): String {
        requireToken(token)
        return seal(deriveCodeKey(joinCode), token, Purpose.Code)
    }

    /** Inverse of [wrapTokenForCode]. Throws on a wrong code or a tampered wrapper. */
    fun unwrapTokenWithCode(joinCode: String, wrapped: String): String {
        val token = open(deriveCodeKey(joinCode), wrapped, Purpose.Code)
        // A wrapper that authenticates but holds something other than a token means the format
        // drifted. Better to fail here than to feed junk into deriveGroupKey and show a blank map
        // with no explanation.
        requireToken(token)
        return token
    }

    // --- base64url ------------------------------------------------------------------------------
    //
    // Hand-rolled: `java.util.Base64` is API 26 against minSdk 24, and `android.util.Base64` would
    // drag an Android dependency into a file that has to stay unit-testable on a plain JVM. The
    // fixture pins both directions, so this is verified rather than trusted.

    private const val B64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    internal fun base64UrlEncode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            out.append(B64URL[(n ushr 18) and 63]).append(B64URL[(n ushr 12) and 63])
                .append(B64URL[(n ushr 6) and 63]).append(B64URL[n and 63])
            i += 3
        }
        // No padding — the contract is unpadded base64url, so the tail is emitted short.
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xFF) shl 16
                out.append(B64URL[(n ushr 18) and 63]).append(B64URL[(n ushr 12) and 63])
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
                out.append(B64URL[(n ushr 18) and 63]).append(B64URL[(n ushr 12) and 63])
                    .append(B64URL[(n ushr 6) and 63])
            }
        }
        return out.toString()
    }

    internal fun base64UrlDecode(value: String): ByteArray {
        if (value.isEmpty()) return ByteArray(0)
        val clean = value.trimEnd('=')
        val out = ByteArray(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        var written = 0
        for (c in clean) {
            val v = B64URL.indexOf(c)
            if (v < 0) throw IllegalArgumentException("not base64url: $c")
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[written++] = ((buffer ushr bits) and 0xFF).toByte()
            }
        }
        return if (written == out.size) out else out.copyOf(written)
    }

    // --- Guards ---------------------------------------------------------------------------------

    private fun requireToken(token: String) {
        if (!TOKEN_PATTERN.matches(token)) {
            throw EnvelopeException("invite token must be 22 base64url characters")
        }
    }

    private fun requireKey(key: ByteArray) {
        if (key.size != KEY_LENGTH_BYTES) {
            throw EnvelopeException("key must be $KEY_LENGTH_BYTES bytes, got ${key.size}")
        }
    }
}
