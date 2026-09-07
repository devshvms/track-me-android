package `in`.shvms.trackme.domain.notifications

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SCOPE_1.8.7 §6.3 — the Class D wire contract, proved against the frozen vectors.
 *
 * Three implementations have to agree on this shape: the admin endpoint that writes it, and two
 * clients that read it. The asymmetry of the failures is why the invalid cases outnumber the valid
 * ones almost four to one — a client that drops a good broadcast costs a delay, while a client that
 * renders a bad one has taken an unvalidated string from the network to a HIGH-importance channel.
 */
class OperatorBroadcastVectorsTest {

    private val vectors: JSONObject =
        JSONObject(File("src/test/resources/operator-broadcast-v1.json").readText())

    /** JSON object -> the untrusted map shape `parse` actually receives in production. */
    private fun record(o: JSONObject): Map<String, Any?> =
        o.keys().asSequence().associateWith { key -> if (o.isNull(key)) null else o.get(key) }

    @Test
    fun `the tag vocabulary is exactly what the contract declares`() {
        val tags = vectors.getJSONArray("tags")
        assertEquals(
            "adding a tag must be a three-codebase change, not a one-line one",
            BroadcastTag.entries.size,
            tags.length(),
        )
        for (i in 0 until tags.length()) {
            assertNotNull(BroadcastTag.parse(tags.getJSONObject(i).getString("id")))
        }
    }

    @Test
    fun `every valid vector parses, and applies where the contract says it applies`() {
        val cases = vectors.getJSONArray("valid")
        assertTrue(cases.length() >= 4)
        for (i in 0 until cases.length()) {
            val vector = cases.getJSONObject(i)
            val description = vector.getString("description")
            val parsed = OperatorBroadcast.parse(record(vector.getJSONObject("record")))
            assertNotNull(description, parsed)
            assertEquals(
                description,
                vector.getBoolean("expected_applies"),
                parsed!!.appliesTo(vector.getString("applies_to_release")),
            )
        }
    }

    @Test
    fun `every invalid vector is refused`() {
        val cases = vectors.getJSONArray("invalid")
        assertTrue("the vector file lost its rejection cases", cases.length() >= 14)
        for (i in 0 until cases.length()) {
            val vector = cases.getJSONObject(i)
            assertNull(
                vector.getString("description"),
                OperatorBroadcast.parse(record(vector.getJSONObject("record"))),
            )
        }
    }

    @Test
    fun `unread matches every vector`() {
        val cases = vectors.getJSONArray("unread")
        for (i in 0 until cases.length()) {
            val vector = cases.getJSONObject(i)
            val broadcast = OperatorBroadcast(
                id = "b",
                tag = BroadcastTag.URGENT,
                title = "t",
                body = "b",
                createdAtMillis = vector.getLong("broadcast_created_at"),
            )
            val lastSeen =
                if (vector.isNull("last_seen_created_at")) null
                else vector.getLong("last_seen_created_at")
            assertEquals(
                vector.getString("description"),
                vector.getBoolean("expected_unread"),
                broadcast.isUnread(lastSeen),
            )
        }
    }

    @Test
    fun `an FCM data payload of all strings parses identically to a Firestore document`() {
        // FCM hands every value over as a String; Firestore hands back real numbers. The same
        // broadcast arriving by two routes must produce the same object, or a push and the
        // foreground read of the same row would disagree about what the user was told.
        val fromFirestore = OperatorBroadcast.parse(
            mapOf(
                "id" to "b1", "tag" to "UPDATE", "title" to "t", "body" to "b",
                "created_at_millis" to 1_757_000_000_000L,
                "applies_to_releases_at_or_below" to "1.8.7",
            )
        )
        val fromPush = OperatorBroadcast.parse(
            mapOf(
                "id" to "b1", "tag" to "UPDATE", "title" to "t", "body" to "b",
                "created_at_millis" to "1757000000000",
                // The release ceiling is a string on both routes now, which is one fewer type the
                // two paths can disagree about.
                "applies_to_releases_at_or_below" to "1.8.7",
            )
        )
        assertEquals(fromFirestore, fromPush)
        assertNotNull(fromPush)
    }

    @Test
    fun `no unknown tag can reach a notification, whatever it is spelled like`() {
        // The single most important rejection in the contract. A client that lets an unknown tag
        // through is a client that will render whatever the next person types into a category box.
        listOf("PROMO", "promo", "Urgent", "URGENT ", " URGENT", "", "MARKETING", "OTHER")
            .forEach { assertNull(it, BroadcastTag.parse(it)) }
        assertNull(BroadcastTag.parse(null))
    }

    @Test
    fun `releases compare numerically, not as text`() {
        // "1.9.9" sorts above "1.10.0" as a string, which would silently exclude every device that
        // most needs an update notice — and the failure looks like the broadcast reaching nobody.
        val cases = vectors.getJSONArray("release_comparison")
        for (i in 0 until cases.length()) {
            val vector = cases.getJSONObject(i)
            assertEquals(
                "${vector.getString("left")} vs ${vector.getString("right")}",
                vector.getInt("expected"),
                ReleaseVersion.compare(vector.getString("left"), vector.getString("right")),
            )
        }
    }

    @Test
    fun `the retired integer ceiling is refused, not quietly ignored`() {
        // v1's key meant a different build on each platform — versionCode 29 here, CFBundleVersion
        // 7 on iOS. Accepting it alongside the new one would let a stale sender target nobody while
        // every test still passed.
        assertNull(
            OperatorBroadcast.parse(
                mapOf(
                    "id" to "x", "tag" to "UPDATE", "title" to "t", "body" to "b",
                    "created_at_millis" to 1L, "applies_to_versions_at_or_below" to 187,
                )
            )
        )
    }

    @Test
    fun `a release ceiling is refused on every tag except update`() {
        // Version filtering exists so an update notice is TRUE for the device that receives it. On
        // any other tag it is a segmentation lever with no operational meaning.
        BroadcastTag.entries.forEach { tag ->
            val parsed = OperatorBroadcast.parse(
                mapOf(
                    "id" to "b", "tag" to tag.name, "title" to "t", "body" to "b",
                    "created_at_millis" to 1L, "applies_to_releases_at_or_below" to "1.8.7",
                )
            )
            if (tag == BroadcastTag.UPDATE) assertNotNull(tag.name, parsed)
            else assertNull(tag.name, parsed)
        }
    }

    @Test
    fun `a broadcast with no ceiling applies to every build`() {
        val broadcast = OperatorBroadcast.parse(
            mapOf("id" to "b", "tag" to "MAINTENANCE", "title" to "t", "body" to "b", "created_at_millis" to 1L)
        )!!
        assertTrue(broadcast.appliesTo("1.0.0"))
        assertTrue(broadcast.appliesTo("99.99.99"))
        assertFalse(broadcast.isUnread(1L))
    }
}
