package `in`.shvms.trackme.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `via_code` must reflect how the member actually joined.
 *
 * Both join paths funnel into `joinWithToken`, which hardcoded `true` — so every link-join was
 * reported as a code-join, to the relay as well as to PostHog. The property was constant, which is
 * worse than absent: it implied a distinction the data could not make, and §2.5's growth loop is
 * built on exactly that comparison between the two channels.
 *
 * Read from source because the failure is invisible at runtime. A hardcoded literal produces a
 * perfectly well-formed event, the dashboard renders, and nothing looks wrong until someone tries
 * to compare the channels months later and finds one of them empty.
 */
class GroupJoinChannelTest {

    private val source: String by lazy {
        sourceFile().readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//.*"), "")
    }

    @Test
    fun `joinWithToken takes the channel rather than assuming it`() {
        assertTrue(
            "joinWithToken must accept viaCode — it is reached from both the code and link paths",
            Regex("""fun joinWithToken\((?:[^)]|\n)*viaCode: Boolean""").containsMatchIn(source),
        )
    }

    @Test
    fun `the channel sent to the relay is not a literal`() {
        assertTrue("the join body must forward viaCode", source.contains("""put("viaCode", viaCode)"""))
        assertFalse(
            "the join body hardcodes viaCode — link-joins will be recorded as code-joins",
            source.contains("""put("viaCode", true)""") || source.contains("""put("viaCode", false)"""),
        )
    }

    @Test
    fun `the joined event reports the channel it was given`() {
        assertTrue(
            "trackGroupMemberJoined must pass through viaCode, not a literal",
            source.contains("trackGroupMemberJoined(joined.memberCount, viaCode = viaCode)"),
        )
    }

    @Test
    fun `each entry point declares its own channel`() {
        // The code path resolves a code; the link path only ever holds a token. If these ever
        // agree, one of them is lying.
        val byCode = source.substringAfter("suspend fun joinByCode").substringBefore("suspend fun joinByToken")
        val byToken = source.substringAfter("suspend fun joinByToken").substringBefore("suspend fun joinWithToken")

        assertTrue("joinByCode must report viaCode = true", byCode.contains("viaCode = true"))
        assertFalse("joinByCode must never report viaCode = false", byCode.contains("viaCode = false"))
        assertTrue("joinByToken must report viaCode = false", byToken.contains("viaCode = false"))
        assertFalse("joinByToken must never report viaCode = true", byToken.contains("viaCode = true"))
    }

    @Test
    fun `every join failure path is counted`() {
        // The funnel's invite_sent -> member_joined step previously had no visible drop: a wrong
        // code, an expired invite and a full group were all indistinguishable from nobody trying.
        assertTrue(
            "the malformed-code path must report a failure",
            source.contains("GroupJoinFailure.MALFORMED_CODE"),
        )
        assertTrue("the expired path must report a failure", source.contains("GroupJoinFailure.EXPIRED"))
        assertTrue(
            "joinWithToken's own catch must report — it returns Result.failure rather than throwing, " +
                "so a relay refusal never reaches the callers' catch blocks",
            source.substringAfter("suspend fun joinWithToken").contains("trackGroupJoinFailed"),
        )
    }

    @Test
    fun `failures are classified by type, never by message text`() {
        // Messages are user-facing prose and get translated; matching on them would silently
        // reclassify every failure the moment a string changed.
        val classifier = source.substringAfter("private fun classifyJoinFailure").substringBefore("private fun sealRoster")
        assertFalse(
            "classifyJoinFailure inspects the exception message",
            classifier.contains(".message"),
        )
        assertTrue("relay codes should map straight through", classifier.contains("GROUP_FULL"))
    }

    private fun sourceFile(): File {
        var dir: File? = File("").absoluteFile
        val rel = "app/src/main/java/in/shvms/trackme/data/remote/GroupSessionManager.kt"
        while (dir != null) {
            File(dir, rel).takeIf { it.exists() }?.let { return it }
            File(dir, rel.removePrefix("app/")).takeIf { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("GroupSessionManager.kt not found")
    }
}
