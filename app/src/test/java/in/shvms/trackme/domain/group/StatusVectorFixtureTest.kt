package `in`.shvms.trackme.domain.group

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The cross-platform status contract — SCOPE_1.7.2 §4.3, and a **hard release gate**.
 *
 * E1 ships Android and iOS together precisely because a divergence here is invisible until a real
 * group is riding: one platform would render nothing where the other renders "Need help", and the
 * sender would believe they had been heard. This file is what stops the two drifting.
 *
 * The same fixture must be executed by the iOS suite. Until it is, this proves only that Android
 * agrees with the written contract — which is necessary but not sufficient.
 */
class StatusVectorFixtureTest {

    private val fixture: JSONObject by lazy {
        val file = File("src/test/resources/group-status-vectors.json")
        JSONObject(file.readText())
    }

    @Test
    fun `every code vector parses exactly as the contract says`() {
        val codes = fixture.getJSONArray("codes")
        for (i in 0 until codes.length()) {
            val v = codes.getJSONObject(i)
            val raw = v.getString("raw")
            val parsed = RiderStatusCodec.parse(raw)

            if (!v.getBoolean("valid")) {
                assertNull("'$raw' must be rejected: ${v.optString("note")}", parsed)
                continue
            }

            assertNotNull("'$raw' must parse", parsed)
            assertEquals("$raw severity", v.getString("severity")[0], parsed!!.severity.digit)
            assertEquals(
                "$raw persona",
                v.optString("persona").takeIf { it.isNotEmpty() && it != "null" }?.get(0),
                parsed.persona?.letter,
            )
            assertEquals("$raw message", v.getString("message"), parsed.message)
            assertEquals(
                "$raw extension",
                v.optString("extension").takeIf { it.isNotEmpty() && it != "null" },
                parsed.extension,
            )
        }
    }

    @Test
    fun `every age vector anchors exactly as the contract says`() {
        val ages = fixture.getJSONArray("ages")
        for (i in 0 until ages.length()) {
            val v = ages.getJSONObject(i)
            val stAge = if (v.isNull("stAgeSeconds")) null else v.getLong("stAgeSeconds")
            val anchor = PresenceAge.anchorStatus(
                serverNowMillis = v.getLong("serverNowMs"),
                serverTsMillis = v.getLong("serverTsMs"),
                stAgeSeconds = stAge,
                receivedAtElapsed = 1_000L,
            )
            if (v.isNull("expectedAgeAtReceiptMs")) {
                assertEquals("${v.optString("note")}: age must be unknown", false, anchor.isKnown)
            } else {
                assertEquals(v.optString("note"), true, anchor.isKnown)
                assertEquals(
                    v.optString("note"),
                    v.getLong("expectedAgeAtReceiptMs"),
                    anchor.ageAtReceiptMillis,
                )
            }
        }
    }

    @Test
    fun `every bucket vector reads exactly as the contract says`() {
        val buckets = fixture.getJSONArray("buckets")
        for (i in 0 until buckets.length()) {
            val v = buckets.getJSONObject(i)
            val actual = PresenceAge.bucketOf(v.getLong("ageMs"), v.getInt("syncIntervalSec"))
            val rendered = when (actual) {
                PresenceAge.Bucket.Now -> "Now"
                PresenceAge.Bucket.Unknown -> "Unknown"
                is PresenceAge.Bucket.Seconds -> "Seconds:${actual.value}"
                is PresenceAge.Bucket.Minutes -> "Minutes:${actual.value}"
                is PresenceAge.Bucket.Hours -> "Hours:${actual.value}"
            }
            assertEquals(v.optString("note"), v.getString("expected"), rendered)
        }
    }
}
