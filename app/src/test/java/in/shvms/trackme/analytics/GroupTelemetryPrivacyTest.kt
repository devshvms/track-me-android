package `in`.shvms.trackme.analytics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SCOPE_1.7.0 §9 — *"a constraint on the analytics, not just on the product."*
 *
 * > *"What we deliberately do not collect: any coordinate, any group name, any member
 * > relationship, any inference about who rides with whom. Aggregate counts only."*
 *
 * The telemetry layer is where that promise is most likely to erode, because every individual
 * addition looks reasonable in isolation — a uid "just for debugging", a group name "to make the
 * dashboard readable". This reads the source of the group events and fails on any of them.
 *
 * It is a blunt test on purpose. A reviewer can miss one property in a `mapOf`, and the harm
 * lands on the people the whole feature is designed to protect.
 */
class GroupTelemetryPrivacyTest {

    private val groupEventBlock: String by lazy {
        val source = File(sourcePath()).readText()
        val start = source.indexOf("// --- Group Ride (§9)")
        val end = source.indexOf("fun trackLiveShareStarted", start)
        require(start >= 0 && end > start) { "group telemetry block not found — did it move?" }
        // Comments carry the reasoning and name the forbidden things, so they are stripped before
        // scanning. Third time this has come up in this feature.
        source.substring(start, end)
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//.*"), "")
    }

    @Test
    fun `no group event carries a coordinate`() {
        for (forbidden in listOf("lat", "lng", "latitude", "longitude", "coordinate", "location")) {
            assertFalse(
                "a group telemetry property mentions \"$forbidden\" — §9 forbids coordinates outright",
                groupEventBlock.lowercase().contains("\"$forbidden"),
            )
        }
    }

    @Test
    fun `no group event carries a user identity`() {
        // §9: no member relationship, no inference about who rides with whom. A uid in any event
        // makes the group graph reconstructable from the analytics alone.
        for (forbidden in listOf("uid", "user_id", "userid", "email", "display_name", "photo")) {
            assertFalse(
                "a group telemetry property mentions \"$forbidden\" — §9 forbids member identity",
                groupEventBlock.lowercase().contains("\"$forbidden"),
            )
        }
    }

    @Test
    fun `no group event carries the group's name`() {
        // The group name is encrypted end to end precisely so it does not leave the members'
        // devices. Sending it to PostHog would undo §5.3 for the sake of a nicer dashboard.
        //
        // Scoped to the property *keys* — what actually gets sent — rather than any occurrence of
        // the word. A blanket substring scan failed the moment a function was legitimately named
        // after the meta it updates, while still sending nothing but two booleans; the rule was
        // never about identifiers. Every key is checked, so `meta`, `meta_blob` and `group_name`
        // are all still caught.
        for (key in propertyKeys()) {
            for (forbidden in listOf("name", "meta")) {
                assertFalse(
                    "group event property \"$key\" contains \"$forbidden\" — the group name and " +
                        "the sealed meta payload must never leave the device",
                    key.contains(forbidden),
                )
            }
        }
    }

    @Test
    fun `join failures report a closed vocabulary, never a message`() {
        // The reason must come from the GroupJoinFailure enum. An exception message would be prose
        // — translated, and capable of carrying relay text straight into an analytics property.
        assertTrue(groupEventBlock.contains("fun trackGroupJoinFailed"))
        assertTrue(
            "trackGroupJoinFailed must take the enum, not a String",
            groupEventBlock.contains("reason: GroupJoinFailure"),
        )
        assertTrue(
            "the reason must be the enum's own value",
            groupEventBlock.contains("reason.analyticsValue"),
        )
    }

    /** Every `"key" to value` property name in the group block. */
    private fun propertyKeys(): List<String> =
        Regex("\"([A-Za-z_][A-Za-z0-9_]*)\"\\s+to\\s").findAll(groupEventBlock)
            .map { it.groupValues[1].lowercase() }
            .toList()

    @Test
    fun `every group event respects the telemetry opt-out`() {
        // Each function must bail on the shared flag. One that forgets would keep reporting after
        // the user turned analytics off, which is a consent violation regardless of payload.
        val functions = Regex("fun (trackGroup\\w+)\\(").findAll(groupEventBlock).map { it.groupValues[1] }.toList()
        assertTrue("no group telemetry functions found", functions.size >= 6)
        val guards = Regex("if \\(!_isTelemetryEnabled\\.value\\) return").findAll(groupEventBlock).count()
        assertTrue(
            "${functions.size} group events but only $guards opt-out guards",
            guards >= functions.size,
        )
    }

    @Test
    fun `the leave event exists, because a missing exit metric hides an undiscoverable exit`() {
        // §9 is explicit and easy to get backwards: "heavy use of the exit controls is a healthy
        // signal, not a problem. Near-zero leave usage most likely means the control is
        // undiscoverable, which is a red flag. Nobody should be tasked with reducing the leave
        // rate." Not measuring it at all is the worst version of that.
        assertTrue(groupEventBlock.contains("fun trackGroupLeft"))
        assertTrue("time-to-first-leave needs the duration", groupEventBlock.contains("seconds_in_group"))
    }

    @Test
    fun `the degraded event exists so a silently-absorbed outage is still visible`() {
        // §8 has clients absorb a relay outage with backoff. Without this, an outage every client
        // handled gracefully would never appear in the ops metrics §9 asks for.
        assertTrue(groupEventBlock.contains("fun trackGroupDegraded"))
    }

    private fun sourcePath(): String {
        var dir: File? = File("").absoluteFile
        val rel = "app/src/main/java/in/shvms/trackme/analytics/AnalyticsManager.kt"
        while (dir != null) {
            File(dir, rel).takeIf { it.exists() }?.let { return it.path }
            File(dir, rel.removePrefix("app/")).takeIf { it.exists() }?.let { return it.path }
            dir = dir.parentFile
        }
        throw AssertionError("AnalyticsManager.kt not found")
    }
}
